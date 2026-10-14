# Google Flow - Text-to-Video + Text-to-Image + Image-to-Video + AI Prompt Assistant in pure Java (Socket Programming)

Java only (JDK built-ins), no libraries, no frameworks. Everything talks over `java.net.Socket / ServerSocket`.
A prompt is turned into a short looping clip (**Text -> Video**) or a still picture (**Text -> Image**) by a **free AI image API** (+ a virtual camera move for video), rendered by a pool of
**worker nodes** that are scheduled by a small **distributed algorithm**.

## How to run (JDK 17+ needed - check with `javac -version`)

```
run.bat build         (Windows)         ./run.sh build         (Linux/Mac)
```
(The first `run web` also builds automatically.) Then open separate terminals in this folder:

| Terminal | Command | What it does |
|---|---|---|
| 1 | `run web`      | website -> open **http://localhost:8080** (also starts the backend + 3 worker nodes inside it) |
| 2 | `run terminal` | terminal Google Flow (can run together with the website, same accounts and clips) |

**Viva mode - every tier as a separate process** (so you can kill a worker live):

| Terminal | Command | What it does |
|---|---|---|
| 1 | `run workers`           | 3 AI worker nodes (ports 9101, 9102, 9103) |
| 2 | `run backend external`  | backend (port 9090) that uses those workers |
| 3 | `run web`               | website -> **http://localhost:8080** |
| 4 | `run terminal`          | terminal client |

(Linux/Mac: `./run.sh web`, etc.  Without scripts: `cd server` then `java -cp src/out GoogleFlowWebServer public storage`)

**AI settings** are in `server/config.properties`. Default = **Pollinations** (free, **no key**, about 1 image / 15 s, so a clip takes ~15-40 s).
Optional backup: free Hugging Face token (`HF_TOKEN=...`). Other options: `FLOW_FALLBACK`, `FLOW_API_TRIES`, `FLOW_FAIL_PROB` (set 0.15 to
make 15% of jobs fail, to demo retries). Environment variables override the file.

## Files (all in `server/src/`)

| File | Role |
|---|---|
| `FlowBackend.java`      | BACKEND (port 9090): users, sessions, library, playlists, quota, job queue, 3 dispatcher threads |
| `Scheduler.java`        | THE DISTRIBUTED ALGORITHM: least-loaded load balancing + heartbeat failure detection + failover |
| `FlowWorkerNode.java`   | WORKER NODE (ports 9101-9103): TCP server; commands `ping`, `generate` (text -> GIF), `generate_image` (text -> PNG) and **`generate_i2v` (picture -> MP4/GIF) and `enhance_prompt` (idea -> cinematic prompt)**; result returned as base64 |
| `PromptAssistant.java`  | inside a worker: **AI Prompt Assistant** - simple idea -> cinematic prompt via a free LLM (Pollinations / Hugging Face / your own OpenAI-compatible API), built-in template as last fallback |
| `I2VPipeline.java`      | inside a worker: **Image -> Video** - uploaded picture + prompt -> 5-6 s clip via a free Hugging Face Space (Gradio HTTP API), optional fal.ai, or a local motion-effect fallback |
| `SceneBuilder.java`     | inside a worker: **Scene Builder / Video Timeline** - stitches Library clips + pictures into ONE movie (per-scene trim, fixed 12.5 fps grid, GIF output in pure Java; optional ffmpeg for MP4 clips / MP4 output) |
| `SceneCommands.java`    | backend side of the Scene Builder: `scene_info`, `scene_preview`, `scene_submit`, timeline validation, ships the chosen clips to a worker |
| `AiPipeline.java`       | inside a worker: prompt -> free AI image API (Pollinations / Hugging Face) -> camera move -> GIF (`generateClip`) **or -> PNG (`generateImage`)**; rate-limit slot queue shared by both |
| `GoogleFlowWebServer.java` | MODE 1: tiny HTTP server (ServerSocket) -> serves `public/` and works as the **API gateway** (`/api/*` -> TCP) |
| `GoogleFlowTerminal.java`  | MODE 2: numbered text menu; a client of the same backend |
| `Net.java`              | the one socket client used by every tier (1 JSON line out, 1 JSON line back) |
| `ClientHandler, HttpRequest, HttpResponse` | HTTP parsing / writing over raw sockets |
| `Database.java, Models.java, Json.java` | storage (serialized to `googleflow.dat`), data classes, hand-written JSON |
| `AnimatedGifWriter.java`, `Config.java` | GIF encoder, settings reader |
| `public/index.html, style.css, app.js` | the website (in `server/public/`) |

Other: `run.bat` / `run.sh` (one launcher for everything), `server/config.properties`, `server/storage/` (generated clips).

## Architecture

```
 Browser --HTTP--> GoogleFlowWebServer (8080) --\
                      (API gateway)              >--TCP/JSON--> FlowBackend (9090) --TCP/JSON--> FlowWorkerNode 9101 \
 GoogleFlowTerminal ---------------------------/                 job queue                    FlowWorkerNode 9102  } share nothing
                                                                 3 dispatchers + Scheduler    FlowWorkerNode 9103 /
                                                                                                    |
                                                                                                    +--HTTPS--> free AI image API
```
Protocol: request = **one JSON line** `{"cmd":"submit", ...}` + newline, reply = **one JSON line** `{"status":"ok", ...}`.
One short TCP connection per request; every server uses one thread per connection.
**Text -> Image (new)** reuses exactly the same path: `submit` with `"kind":"image"` -> job queued -> Scheduler picks a node -> worker command `generate_image` -> PNG comes back as base64 -> backend stores `<jobid>.png` -> `/media/<file>` serves it as `image/png`. Image options: style (realistic, cartoon, cinematic, anime, painting, 3d), aspect (16:9, 9:16, 1:1), model (fast 768px / quality 1024px). Images and videos share the same quota, load balancing, heartbeat, failover and retry logic. Old data files still load (jobs/clips without a `kind` are treated as videos).

**Image -> Video (new)**: the browser shrinks the uploaded picture (max 1024 px JPEG) and sends it as base64 inside the JSON line (`submit` with `"kind":"i2v"`, `image_b64`, `prompt_text`, `seconds` 5 or 6). The backend validates it, keeps it in `storage/src_<job>.jpg` while the job runs (needed for retries; deleted afterwards, never served), queues the job, and the Scheduler sends the picture + prompt to a worker (`generate_i2v`). The worker (`I2VPipeline`) tries, in order: (1) free **Hugging Face Spaces** (LTX-Video, Wan2.2) through the Gradio HTTP API - read `/info`, upload the picture, call the endpoint, read the event stream, download the MP4; (2) **fal.ai** if `FAL_KEY` is set; (3) a **local motion effect** (jump / zoom / pan / fall / shake ... chosen from the prompt words) - this one is *not AI*, it only moves your picture, and the History / job status shows which engine really made the clip. Clips are `.mp4`/`.webm` (AI) or `.gif` (local); the website plays them with `<video>` / `<img>` automatically. Settings: `server/config.properties`.

**AI Prompt Assistant (new)**: press **✨ Enhance with AI** under any prompt box on the website (Text -> Video, Text -> Image, Image -> Video), or answer `y` to "Improve it with the AI Prompt Assistant?" in the terminal (also menu **11**). The browser sends `enhance` {idea, target, style, seed} to the gateway -> backend (login required, content moderation, 1 request/second per user, no quota used) -> **Scheduler picks a worker node** -> command `enhance_prompt` -> `PromptAssistant` asks an LLM (instructions differ for video / image / image->video) and cleans the answer to <= 420 characters. "Another version" uses a new seed. The reply says which engine wrote the prompt: `llm:pollinations`, `llm:huggingface`, `llm:custom` or `local-template (not an LLM)`; if no worker node is reachable the backend itself uses the template, so the button never fails.

**Scene Builder / Video Timeline (new)**: website tab **✂️ Scene Builder** (terminal menu **12**). Build one movie from what is already in your Library - no generation credits are used.
- **Timeline**: add clips / pictures from your Library (or a whole playlist) with ➕ Add; **rearrange** by drag & drop or ◀ ▶; **⧉ Copy** uses the same clip twice (e.g. two different trims); ✕ removes. The coloured bar shows how long each scene is; the total length is shown live. The timeline is remembered in the browser.
- **Trim**: every video scene has Start / End sliders (0.08 s steps); a picture has "Show for 0.5-10 s".
- **Preview sequence**: the backend asks a worker node for a quick, low-resolution render of the *exact* timeline (same order and trims) and the page plays it - nothing is saved.
- **Generate movie**: queues a normal job of kind `scene` -> Scheduler (least-loaded, failover, retry) -> worker command `stitch`. The finished movie is saved in the Library as a new clip (and can be used as a scene again). Options: size (from first scene / 16:9 / 9:16 / 1:1, other shapes get black bars, nothing is stretched), output GIF or MP4.
- Limits: 20 scenes, 120 s total, 60 MB of source clips. A movie from your own clips does not use the monthly quota.
- Protocol: `scene_submit` / `scene_preview` {title, aspect, format, scenes:[{asset_id, start, end (0 = to the end), hold}]}. The backend reads the files from `storage/` and sends them to the worker inside the `stitch` request as base64 (workers still share nothing). Old `googleflow.dat` files load unchanged.
- **ffmpeg is optional.** Without it everything works for GIF clips and pictures (GIF output). With `ffmpeg` on your PATH (or `FLOW_FFMPEG=` in `config.properties`) you can also use MP4/WEBM clips (Image -> Video AI clips) as scenes and choose MP4 output (needs libx264, included in normal ffmpeg builds).

**Character & Asset Consistency / My Assets (new)**: website tab **🧍 My Assets**.
- **Upload**: a picture of a person, object or location + a name, a type and an optional description. Pictures are re-encoded to JPEG (max 1024 px) and stored as `storage/ref_<id>.jpg`; the records are in their own file `refassets.dat` (so `googleflow.dat` and the Library are untouched). Max 30 assets per user.
- **Gallery**: small cards with the picture, name, description, and buttons to use it (🎬 Video, 🖼 Image, 📷 Animate), edit (✏) or delete (🗑).
- **Reuse as reference**: Text -> Video, Text -> Image and Image -> Video pages get a *Reference asset* dropdown. Image -> Video uses the asset's picture itself as the start picture. For the text pages the asset's description is added to the prompt, so the same character / object / place is described identically every time (the free image/video engines cannot take a reference picture for text prompts).
- Backend commands: `ref_upload`, `ref_list`, `ref_update`, `ref_delete` (`RefCommands.java`).

A job's life: `submit` -> quota reserved -> job queued (`Waiting`) -> dispatcher picks it (`Processing`) -> Scheduler sends it to a worker ->
worker returns the clip as base64 -> backend saves the file (`Completed`) -> the page polls `status` every second.

## Distributed paradigms demonstrated (viva points)

1. **Client-Server, 3-tier** - website and terminal are clients; the gateway/backend is the application tier; worker nodes are the compute tier. Every hop is a raw socket.
2. **Message passing (no shared memory)** - tiers exchange only JSON lines over TCP (`Net.call`); workers share nothing with the backend, even the finished clip travels back inside the reply (base64).
3. **API gateway / protocol translation** - `GoogleFlowWebServer` is a hand-written HTTP/1.1 server that turns `/api/<cmd>` into the JSON-over-TCP protocol.
4. **Master-worker (coordinator + stateless workers)** - the backend owns all state; workers only render. Any worker can take any job.
5. **Asynchronous processing / producer-consumer** - `submit` returns a job id at once; jobs wait in a `LinkedBlockingQueue` and 3 dispatcher threads consume them; the client polls the status.
6. **Load balancing (least-loaded)** - `Scheduler.pick()` sends each job to the live node with the fewest in-flight jobs (see the Cluster tab / menu 7).
7. **Fault tolerance** - heartbeat failure detection, failover to another node, retry with exponential backoff, and quota refund when a job finally fails.
   Demo: Cluster tab -> **Crash** a node while a job runs (or Ctrl+C a worker in viva mode); the job still completes on another node.
8. **Concurrency / multithreading** - thread per connection, `synchronized` scheduler and database, `ConcurrentHashMap`, `AtomicInteger` in-flight counters, a heartbeat daemon thread.
9. **Transparency** - the user sees one service: same protocol for web and terminal (access), no idea which node renders (location), node crashes are hidden by failover (failure).
10. **Rate limiting / admission control** - a time-slot queue in `AiPipeline.throttle()` shared by all nodes keeps requests to the free AI API within its limit; if one AI service fails, the next provider is tried.
11. **Resource management** - each user has a monthly quota (5 clips), reserved when the job is submitted and refunded if it fails (compensation).

## The algorithms in `Scheduler.java` (say this in the viva)

1. **Least-loaded load balancing** - choose the UP node with the fewest in-flight jobs; tie -> the node that has finished fewer jobs.
2. **Heartbeat failure detection** - every 3 s each node is pinged (1.5 s timeout); no reply -> DOWN, reply again -> UP again (automatic recovery).
3. **Failover** - if a node cannot be reached while sending a job, mark it DOWN at once and send the **same job** to another node not yet tried.
4. **Retry with exponential backoff** (`FlowBackend.process`) - if a job still fails: wait 1 s, then 2 s (+ small random jitter), at most 2 retries, then `Failed` + quota refund.

## Limitations (say this in viva)

- Prompt Assistant: the free Pollinations text API can change or rate-limit without notice; then the built-in template (not an LLM, just fixed cinematic phrases around your idea) is used. A free `HF_TOKEN` or your own `FLOW_LLM_URL` makes it reliable. The LLM code was tested against a mock OpenAI-compatible server, not live services.
- Image -> Video: free AI Spaces can be asleep, queued for minutes or out of GPU quota (a free `HF_TOKEN` helps); then the local motion effect is used - it cannot invent new motion like a real dog jumping, it only animates your picture. The AI-Space/fal.ai code was tested against a mock Gradio server, not against the live services.
- Scene Builder: output is built on a 12.5 fps grid at the standard clip size (480x270 / 270x480 / 360x360), so it is as sharp as the source clips; there is no audio (the clips have none) and no transitions. GIF is the default output because it needs no extra software; MP4 needs ffmpeg. Trimming a GIF/MP4 is frame accurate to 0.08 s.
- the backend is a single coordinator and its data is one serialized file. 
- Replication, consensus and logical clocks are intentionally not implemented, to keep the design simple. The distribution is in the scheduling and fault tolerance of the render workers.

## Terminal menu
```
Sign up / Log in (email + password)
1) Home          2) Text -> Video   3) Search
4) Library       5) Playlists       6) History
7) Cluster       8) Account/Quota   9) Text -> Image   10) Image -> Video   11) AI Prompt Assistant   12) Scene Builder
0) Log out
```
In the Library: `l` like, `p` publish, `d` delete, `e` extend, `a` add to playlist, `s` save the GIF to disk.

Website: **Sign up / Log in** card, then Home (recent clips + Flow TV = everyone's published clips), Search, **Text -> Video** (prompt, style, camera,
aspect 16:9 / 9:16, Veo Fast / Quality), **Text -> Image** (prompt, style, aspect 16:9 / 9:16 / 1:1, Fast / Quality; result shown full size), Library (All / Videos / Images filter; like, publish, download, delete, Extend), Playlists (create, add/remove, Play all),
**Scene Builder** (timeline: add, drag to rearrange, trim, preview, generate), History (status, node that rendered, retries), **Cluster** (live node table, crash/revive buttons), Account (quota).