#!/usr/bin/env python3
"""Writes a small, loadable GGUF chat model with RANDOM weights, for host runs where no trained model can be downloaded.

It is a real llama.cpp model in every respect the app's code path cares about (GGUF v3, `llama` architecture, the real
32,000-piece SentencePiece vocabulary copied from llama.cpp's own `models/ggml-vocab-llama-spm.gguf`, a ChatML chat
template, a stated context length), so loading, prompt evaluation, token streaming, stopping and context limits run for
real through llama.cpp. Its replies are meaningless: the weights are noise. It proves nothing about answer quality.

Usage: make_synthetic_gguf.py <llama.cpp checkout> <out.gguf> [--context N] [--no-template] [--tool-template] [--split-count N]
--tool-template stores llama.cpp's own copy of the Qwen 2.5 chat template (ChatML with <tool_call> blocks) instead of
the plain ChatML one, so the file reads as a tool-capable model. The weights are still noise.
Needs numpy and the checkout's own gguf-py (no network).
"""
import argparse
import sys
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("llama_cpp")
parser.add_argument("out")
parser.add_argument("--context", type=int, default=2048)
parser.add_argument("--no-template", action="store_true")
parser.add_argument("--tool-template", action="store_true")
parser.add_argument("--split-count", type=int, default=0)
args = parser.parse_args()

sys.path.insert(0, str(Path(args.llama_cpp) / "gguf-py"))
import numpy as np  # noqa: E402
import gguf  # noqa: E402

vocab = gguf.GGUFReader(str(Path(args.llama_cpp) / "models" / "ggml-vocab-llama-spm.gguf"))


def field(name):
    f = vocab.fields[name]
    return f.contents()


tokens = field("tokenizer.ggml.tokens")
n_vocab, n_embd, n_head, n_layer, n_ff = len(tokens), 64, 4, 2, 128
rng = np.random.default_rng(20261111)


def weights(*shape):
    return (rng.standard_normal(shape) * 0.05).astype(np.float32)


w = gguf.GGUFWriter(args.out, "llama")
w.add_name("dca-synthetic-random-weights")
w.add_context_length(args.context)
w.add_embedding_length(n_embd)
w.add_block_count(n_layer)
w.add_feed_forward_length(n_ff)
w.add_head_count(n_head)
w.add_head_count_kv(n_head)
w.add_layer_norm_rms_eps(1e-5)
w.add_rope_dimension_count(n_embd // n_head)
w.add_file_type(gguf.LlamaFileType.ALL_F32)
w.add_tokenizer_model("llama")
w.add_token_list(tokens)
w.add_token_scores(field("tokenizer.ggml.scores"))
w.add_token_types(field("tokenizer.ggml.token_type"))
w.add_bos_token_id(1)
w.add_eos_token_id(2)
w.add_unk_token_id(0)
if args.tool_template:
    w.add_chat_template((Path(args.llama_cpp) / "models" / "templates" / "Qwen-Qwen2.5-7B-Instruct.jinja").read_text())
elif not args.no_template:
    w.add_chat_template(
        "{% for message in messages %}<|im_start|>{{ message['role'] }}\n{{ message['content'] }}<|im_end|>\n{% endfor %}"
        "{% if add_generation_prompt %}<|im_start|>assistant\n{% endif %}"
    )
if args.split_count:
    w.add_uint16("split.count", args.split_count)
    w.add_uint16("split.no", 0)

w.add_tensor("token_embd.weight", weights(n_vocab, n_embd))
for i in range(n_layer):
    p = f"blk.{i}."
    w.add_tensor(p + "attn_norm.weight", np.ones(n_embd, dtype=np.float32))
    for name in ("attn_q", "attn_k", "attn_v", "attn_output"):
        w.add_tensor(p + name + ".weight", weights(n_embd, n_embd))
    w.add_tensor(p + "ffn_norm.weight", np.ones(n_embd, dtype=np.float32))
    w.add_tensor(p + "ffn_gate.weight", weights(n_ff, n_embd))
    w.add_tensor(p + "ffn_down.weight", weights(n_embd, n_ff))
    w.add_tensor(p + "ffn_up.weight", weights(n_ff, n_embd))
w.add_tensor("output_norm.weight", np.ones(n_embd, dtype=np.float32))
w.add_tensor("output.weight", weights(n_vocab, n_embd))

w.write_header_to_file()
w.write_kv_data_to_file()
w.write_tensors_to_file()
w.close()
print(f"wrote {args.out}: {n_vocab} tokens, context {args.context}")
