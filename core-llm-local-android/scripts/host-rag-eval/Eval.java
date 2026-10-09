import ai.droidcommand.llm.local.LocalEmbedder;
import ai.droidcommand.llm.local.android.LlamaCppEmbeddingBackend;
import ai.droidcommand.rag.Chunk;
import ai.droidcommand.rag.InMemoryVectorStore;
import ai.droidcommand.rag.ScoredChunk;
import ai.droidcommand.rag.TextChunker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Host-only retrieval evaluation: real chunker + vector store + LocalEmbedder + LlamaCppEmbeddingBackend with a real
 * GGUF embedding model, against a word-overlap baseline. Retrieval only (is the right passage returned), not answers.
 * Args: model.gguf corpusDir qa.tsv unrelated.tsv
 */
public class Eval {
    record QA(String doc, String question, String fragment) {}
    record Neg(String kind, String question) {}

    static String norm(String s) { return s.replaceAll("\\s+", " ").trim(); }

    static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        for (String t : s.toLowerCase().split("\\W+")) if (t.length() > 2) w.add(t);
        return w;
    }

    static double overlap(Set<String> q, String chunk) {
        if (q.isEmpty()) return 0;
        Set<String> c = words(chunk);
        int n = 0;
        for (String x : q) if (c.contains(x)) n++;
        return (double) n / q.size();
    }

    static List<String[]> tsv(Path p) throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(p)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            rows.add(line.split("\t"));
        }
        return rows;
    }

    static double pct(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return Double.NaN;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(p * (sorted.size() - 1) + 0.5)));
    }

    public static void main(String[] a) throws Exception {
        Path corpus = Path.of(a[1]);
        List<QA> qas = new ArrayList<>();
        for (String[] r : tsv(Path.of(a[2]))) qas.add(new QA(r[0], r[1], norm(r[2])));
        List<Neg> negs = new ArrayList<>();
        for (String[] r : tsv(Path.of(a[3]))) negs.add(new Neg(r[0], r[1]));

        Map<String, String> docs = new LinkedHashMap<>();
        for (QA q : qas) docs.computeIfAbsent(q.doc, d -> {
            try { return Files.readString(corpus.resolve(d)); } catch (Exception e) { throw new RuntimeException(e); }
        });
        // Ground-truth sanity: every fragment must exist in its document, and be short enough to fit in the chunk overlap.
        boolean bad = false;
        for (QA q : qas) {
            if (!norm(docs.get(q.doc)).contains(q.fragment)) { System.out.println("BAD FRAGMENT (not in " + q.doc + "): " + q.fragment); bad = true; }
            if (q.fragment.split(" ").length > 16) { System.out.println("FRAGMENT LONGER THAN 16 WORDS: " + q.fragment); bad = true; }
        }
        if (bad) System.exit(2);
        System.out.println("docs=" + docs.size() + " answerable questions=" + qas.size() + " unrelated questions=" + negs.size()
            + " words/doc=" + docs.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().split("\\s+").length).collect(Collectors.joining(", ")));

        LlamaCppEmbeddingBackend be = new LlamaCppEmbeddingBackend();
        be.load(a[0]);
        int[][] cfg = {{512, 64}, {256, 32}, {128, 16}};
        double[] thresholds = {0.2, 0.5, 0.55, 0.6, 0.65};
        for (String mode : new String[] {"prefixed", "no-prefix"}) {
            LocalEmbedder emb = mode.equals("prefixed") ? new LocalEmbedder(be, "search_document: ", "search_query: ") : new LocalEmbedder(be, "", "");
            for (int[] c : cfg) {
                Map<String, InMemoryVectorStore> stores = new LinkedHashMap<>();
                Map<String, Integer> chunkCount = new LinkedHashMap<>();
                List<String> failed = new ArrayList<>();
                for (Map.Entry<String, String> d : docs.entrySet()) {
                    List<Chunk> chunks = new TextChunker(c[0], c[1]).chunk(d.getKey(), d.getValue());
                    try {
                        List<float[]> vecs = emb.embed(chunks.stream().map(Chunk::getText).toList());
                        InMemoryVectorStore s = new InMemoryVectorStore();
                        s.add(chunks, vecs);
                        stores.put(d.getKey(), s);
                        chunkCount.put(d.getKey(), chunks.size());
                    } catch (RuntimeException e) {
                        failed.add(d.getKey() + " (" + e.getMessage() + ")");
                    }
                }
                int n = 0, top1 = 0, top3 = 0, lt1 = 0, lt3 = 0;
                double rr = 0, lrr = 0;
                List<Double> pos = new ArrayList<>();
                int[] delivered = new int[thresholds.length]; // top-4 chunks >= t contain the fragment (DocumentRetriever default topK=4)
                Map<String, int[]> perDoc = new LinkedHashMap<>();
                for (QA q : qas) {
                    InMemoryVectorStore s = stores.get(q.doc);
                    if (s == null) continue;
                    n++;
                    List<ScoredChunk> hits = s.search(emb.embedQuery(q.question), 1000);
                    int rank = -1;
                    for (int i = 0; i < hits.size(); i++) if (hits.get(i).getChunk().getText().contains(q.fragment)) { rank = i; break; }
                    if (rank == 0) top1++;
                    if (rank >= 0 && rank < 3) top3++;
                    if (rank >= 0) rr += 1.0 / (rank + 1);
                    pos.add((double) hits.get(0).getScore());
                    for (int t = 0; t < thresholds.length; t++)
                        for (int i = 0; i < Math.min(4, hits.size()); i++)
                            if (hits.get(i).getScore() >= thresholds[t] && hits.get(i).getChunk().getText().contains(q.fragment)) { delivered[t]++; break; }
                    // lexical baseline over the same chunks
                    Set<String> qw = words(q.question);
                    List<Chunk> lex = new ArrayList<>(hits.stream().map(ScoredChunk::getChunk).toList());
                    lex.sort((x, y) -> Double.compare(overlap(qw, y.getText()), overlap(qw, x.getText())));
                    int lr = -1;
                    for (int i = 0; i < lex.size(); i++) if (lex.get(i).getText().contains(q.fragment)) { lr = i; break; }
                    if (lr == 0) lt1++;
                    if (lr >= 0 && lr < 3) lt3++;
                    if (lr >= 0) lrr += 1.0 / (lr + 1);
                    int[] pd = perDoc.computeIfAbsent(q.doc, k -> new int[3]);
                    pd[0]++; if (rank == 0) pd[1]++; if (rank >= 0 && rank < 3) pd[2]++;
                }
                List<Double> far = new ArrayList<>(), near = new ArrayList<>();
                int[] injFar = new int[thresholds.length], injNear = new int[thresholds.length];
                int farN = 0, nearN = 0;
                for (Neg ng : negs) {
                    float[] qv = emb.embedQuery(ng.question());
                    for (InMemoryVectorStore s : stores.values()) {
                        List<ScoredChunk> h = s.search(qv, 1);
                        double best = h.get(0).getScore();
                        boolean isFar = ng.kind().equals("far");
                        (isFar ? far : near).add(best);
                        if (isFar) farN++; else nearN++;
                        for (int t = 0; t < thresholds.length; t++) if (best >= thresholds[t]) { if (isFar) injFar[t]++; else injNear[t]++; }
                    }
                }
                Collections.sort(pos); Collections.sort(far); Collections.sort(near);
                System.out.printf("%n== %s chunk=%d/%d  chunks=%s%s%n", mode, c[0], c[1], chunkCount.values().stream().mapToInt(i -> i).sum(),
                    chunkCount, failed.isEmpty() ? "" : "  FAILED DOCS: " + failed);
                System.out.printf("   real:      top1=%d/%d top3=%d/%d MRR=%.3f%n", top1, n, top3, n, rr / n);
                System.out.printf("   word-overlap: top1=%d/%d top3=%d/%d MRR=%.3f%n", lt1, n, lt3, n, lrr / n);
                System.out.printf("   best-chunk score  on-topic: min=%.3f p10=%.3f median=%.3f | unrelated(far): median=%.3f p90=%.3f max=%.3f | same-domain(near): median=%.3f p90=%.3f max=%.3f%n",
                    pos.get(0), pct(pos, 0.1), pct(pos, 0.5), pct(far, 0.5), pct(far, 0.9), far.get(far.size() - 1), pct(near, 0.5), pct(near, 0.9), near.get(near.size() - 1));
                StringBuilder sb = new StringBuilder("   minScore -> answer delivered in top-4 | false excerpt far | near:");
                for (int t = 0; t < thresholds.length; t++)
                    sb.append(String.format("  %.2f: %d%% | %d%% | %d%%", thresholds[t], Math.round(100.0 * delivered[t] / n), Math.round(100.0 * injFar[t] / farN), Math.round(100.0 * injNear[t] / nearN)));
                System.out.println(sb);
                if (c[0] == 128 && mode.equals("prefixed"))
                    for (Map.Entry<String, int[]> e : perDoc.entrySet()) System.out.printf("   per-doc %-40s top1=%d/%d top3=%d/%d%n", e.getKey(), e.getValue()[1], e.getValue()[0], e.getValue()[2], e.getValue()[0]);
            }
        }
        be.unload();
    }
}
