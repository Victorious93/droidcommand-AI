# Connect DroidCommand to your own n8n

n8n is a workflow program you run on a computer you own. This app can talk
to that computer: list your workflows, start a webhook workflow after you
confirm, and (only if you turn it on) let the AI ask to do the same.

This app never creates an n8n account for you and never stores a key in
git. The key stays encrypted on this phone.

## 1. Run n8n on a PC or a Raspberry Pi

On the computer that will host n8n, install Docker, then:

```
docker run -d --name n8n -p 5678:5678 -v n8n_data:/home/node/.n8n n8nio/n8n
```

Open a browser on that computer: `http://localhost:5678`

Create the first n8n owner account when n8n asks. That account lives on
*your* computer.

Find this computer's address on your home network (for example
`192.168.1.20`). From your phone, the address is usually
`http://192.168.1.20:5678` — not `localhost` (localhost on the phone means
the phone itself).

To stop it later: `docker stop n8n`. To start it again: `docker start n8n`.

## 2. Make an API key in n8n

In n8n: Settings → n8n API → Create an API key.

Copy the key once. n8n will not show the full key again.

## 3. Put the address and key in this app

On the phone: **Advanced** → **n8n (self-hosted)**.

1. Enter the server address (`https://…` or, on your home network only,
   `http://192.168.x.x:5678`).
2. Paste the API key and tap **Save key**. The key is stored encrypted on
   this phone. It is not shown again.
3. Tap **Test connection**. You should see how many workflows n8n found.

Plain HTTP is allowed only for a home/LAN address. The app warns you,
because anyone on that network could see the traffic. Prefer HTTPS when
you can (a reverse proxy on the same computer is enough).

Public `http://` addresses are refused. Cloud-metadata addresses are
refused.

## 4. Run a webhook workflow

A workflow can be started from this app only if it has a **Webhook** node
and is switched **on** in n8n.

1. Tap **Load workflows**.
2. Pick one.
3. Type the JSON to send (or leave `{}`).
4. Tap **Run (asks first)** and confirm.

The result is shown in plain words, not a raw dump.

The webhook call does **not** send your API key. The webhook path is what
n8n uses to accept that request.

## 5. AI / Kai tools (off until you turn them on)

The AI can use two tools: `n8n_list_workflows` and `n8n_run_workflow`.

Both stay **off** until you turn on **Let the AI use n8n**. Turning that
on asks you first. Even after it is on, this phone asks before every run.

Leave it off unless you want the AI to ask.

## If something goes wrong

- **Could not reach the server.** Is n8n running? Is the address the
  phone's view of that computer (not `localhost`)? Are the phone and the
  computer on the same Wi‑Fi?
- **API key was rejected.** Create a new key in n8n and save it again.
- **No webhook.** Add a Webhook node and turn the workflow on in n8n.
- **Workflow is off.** Open n8n and switch it on.

Do not put the API key in a chat, a screenshot, or a git commit.
