import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.List;

/**
 * Google Flow - AI PROMPT ASSISTANT (runs INSIDE a worker node, command "enhance_prompt")
 * ======================================================================================
 * Input : a simple idea  ("a dog jumping into a pool")  +  what it is for (video / image / i2v)
 * Output: ONE rich cinematic prompt (subject, setting, lighting, lens, mood, camera move ...).
 *
 * Engines, tried in the order of FLOW_PROMPT_PROVIDER (default "custom,pollinations,huggingface"):
 *   custom       : any OpenAI-compatible chat API you configure (FLOW_LLM_URL / FLOW_LLM_KEY / FLOW_LLM_MODEL)
 *                  e.g. Groq, OpenRouter, a local Ollama ("http://localhost:11434/v1/chat/completions") ...
 *   pollinations : FREE text AI, no key  (https://text.pollinations.ai/openai)
 *   huggingface  : needs HF_TOKEN (free) - Hugging Face router, model HF_LLM_MODEL
 *   local        : ALWAYS available - a built-in cinematic template (not an LLM). Used when no AI answers,
 *                  so the button never fails. The reply says which engine wrote the prompt.
 */
public class PromptAssistant {

    static final String PROVIDERS = Config.get("FLOW_PROMPT_PROVIDER", "custom,pollinations,huggingface").toLowerCase();
    static final String LLM_URL   = Config.get("FLOW_LLM_URL", "");
    static final String LLM_KEY   = Config.get("FLOW_LLM_KEY", "");
    static final String LLM_MODEL = Config.get("FLOW_LLM_MODEL", "");
    static final String POLLI_URL = Config.get("POLLINATIONS_TEXT_URL", "https://text.pollinations.ai/openai");
    static final String POLLI_LLM = Config.get("POLLINATIONS_TEXT_MODEL", "openai");
    static final String HF_URL    = Config.get("HF_CHAT_URL", "https://router.huggingface.co/v1/chat/completions");
    static final String HF_LLM    = Config.get("HF_LLM_MODEL", "meta-llama/Llama-3.1-8B-Instruct");
    static final int    TIMEOUT_S = Config.getInt("FLOW_PROMPT_TIMEOUT_S", 12);     // per provider; keeps the button snappy
    static final int    TOTAL_S   = Config.getInt("FLOW_PROMPT_TOTAL_S", 20);       // total budget over all providers
    static final int    MAX_CHARS = 420;     // AiPipeline keeps only the last ~450 characters of a prompt, so stay below that

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** Returns {prompt, engineName}. Never throws: falls back to the local template. */
    public static Object[] expand(String idea, String target, String style, long seed) {
        idea = idea == null ? "" : idea.trim().replaceAll("\\s+", " ");
        if (idea.length() > 500) idea = idea.substring(0, 500);
        String t = target == null ? "video" : target.toLowerCase();
        String lastError = null;
        long deadline = System.currentTimeMillis() + TOTAL_S * 1000L;       // whole assistant answers within ~20 s, then falls back
        for (String p : PROVIDERS.split("[,\\s]+")) {
            if (System.currentTimeMillis() > deadline - 2000) break;
            try {
                String text = null, engine = null;
                if (p.equals("custom") && !LLM_URL.isBlank()) {
                    text = chat(LLM_URL, LLM_KEY, LLM_MODEL.isBlank() ? "gpt-4o-mini" : LLM_MODEL, idea, t, style, seed, deadline);
                    engine = "llm:custom (" + LLM_MODEL + ")";
                } else if (p.equals("pollinations")) {
                    text = chat(POLLI_URL, "", POLLI_LLM, idea, t, style, seed, deadline);
                    engine = "llm:pollinations";
                } else if (p.equals("huggingface") && !AiPipeline.HF_TOKEN.isBlank()) {
                    text = chat(HF_URL, AiPipeline.HF_TOKEN, HF_LLM, idea, t, style, seed, deadline);
                    engine = "llm:huggingface (" + HF_LLM + ")";
                }
                if (text != null) {
                    String clean = clean(text);
                    if (clean.length() >= 20) return new Object[]{clean, engine};
                    lastError = p + " returned an unusable answer";
                }
            } catch (Exception e) {
                lastError = p + ": " + e.getMessage();
            }
            if (lastError != null) System.out.println("[assistant] " + lastError);
        }
        System.out.println("[assistant] no LLM answered (" + lastError + ") -> built-in cinematic template");
        return new Object[]{localTemplate(idea, t, style, seed), "local-template (not an LLM)"};
    }

    // ------------------------------------------------------------- LLM call ---
    @SuppressWarnings("unchecked")
    private static String chat(String url, String key, String model, String idea, String target, String style, long seed, long deadline) throws Exception {
        List<Object> msgs = new ArrayList<>();
        msgs.add(msg("system", systemPrompt(target, style)));
        msgs.add(msg("user", "Idea: " + idea));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("temperature", 0.9);
        body.put("max_tokens", 260);
        body.put("seed", seed & 0x7fffffff);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(Math.max(2000, Math.min(TIMEOUT_S * 1000L, deadline - System.currentTimeMillis()))))
                .header("Content-Type", "application/json").header("User-Agent", "GoogleFlowJava/1.0");
        if (!key.isBlank()) b.header("Authorization", "Bearer " + key);
        HttpResponse<String> r = HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new Exception("HTTP " + r.statusCode());
        Object choices = Json.parseObject(r.body()).get("choices");
        if (choices instanceof List && !((List<Object>) choices).isEmpty()) {
            Object c0 = ((List<Object>) choices).get(0);
            if (c0 instanceof Map && ((Map<String, Object>) c0).get("message") instanceof Map) {
                Object content = ((Map<String, Object>) ((Map<String, Object>) c0).get("message")).get("content");
                if (content instanceof String) return (String) content;
            }
        }
        throw new Exception("reply not in OpenAI chat format");
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role); m.put("content", content);
        return m;
    }

    private static String systemPrompt(String target, String style) {
        String s = "You are a film cinematographer and prompt engineer for AI image and video generators. "
                + "Turn the user's simple idea into ONE vivid cinematic prompt of 45 to 65 words, written as a single paragraph. "
                + "Keep the user's subject and intent. Add concrete detail: setting, time of day, lighting, color palette, lens or shot type, mood. "
                + "Output ONLY the prompt text - no title, no preamble, no quotes, no markdown, no lists, no text overlays, no brand names, no real people, nothing violent or sexual. ";
        switch (target) {
            case "image": s += "It is for a single still image: describe one frozen frame - composition, depth of field, textures. No motion words."; break;
            case "i2v":   s += "A picture already exists and will be animated into a 5 second clip: describe ONLY how the existing subject and scene move "
                             + "(the subject's action, physics, water or wind, one camera movement). Do not re-describe what the picture looks like."; break;
            default:      s += "It is for a 5 second video clip: include what the subject does over time and exactly one camera movement (push-in, pan, tracking shot, tilt, orbit ...).";
        }
        if (style != null && !style.isBlank()) s += " Visual style: " + style + ".";
        return s;
    }

    /** Removes quotes / "Prompt:" labels / markdown and trims to MAX_CHARS at a sentence or word boundary. */
    static String clean(String t) {
        t = t.replaceAll("(?s)<think>.*?</think>", "").replaceAll("[`*#_>]", "").replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("(?i)^(here('s| is)[^:]{0,60}:|prompt:|cinematic prompt:)\\s*", "");
        t = t.replaceAll("^[\"'“”]+|[\"'“”]+$", "").trim();
        if (t.length() > MAX_CHARS) {
            String cut = t.substring(0, MAX_CHARS);
            int dot = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf(".\""));
            t = dot > 200 ? cut.substring(0, dot + 1) : cut.substring(0, Math.max(cut.lastIndexOf(' '), 100)).replaceAll("[,;:]$", "") + ".";
        }
        return t;
    }

    // ----------------------------------------------------- local fallback ---
    private static final String[] SHOT = {"Wide establishing shot", "Low-angle hero shot", "Intimate medium close-up", "Cinematic wide frame", "Dynamic three-quarter shot"};
    private static final String[] LIGHT = {"golden-hour sunlight with long soft shadows", "moody blue-hour light with warm practical glows",
            "dramatic backlight with a soft rim glow", "diffused overcast light with rich natural color", "high-contrast chiaroscuro lighting with deep shadows"};
    private static final String[] LENS = {"shot on a 35mm anamorphic lens with shallow depth of field", "85mm portrait lens, creamy bokeh",
            "24mm wide lens with crisp foreground detail", "50mm prime lens, subtle film grain"};
    private static final String[] GRADE = {"teal-and-orange color grade", "rich saturated colors", "muted cinematic palette with warm highlights", "vivid, high dynamic range look"};
    private static final String[] MOOD = {"epic and awe-inspiring", "serene and dreamlike", "energetic and joyful", "tense and atmospheric", "warm and nostalgic"};
    private static final String[] CAM = {"slow push-in", "smooth tracking shot following the subject", "gentle left-to-right pan", "slow upward tilt", "sweeping orbit around the subject"};
    private static final String[] MOTION_I2V = {"the subject springs into motion with natural, weighty physics, water and fabric reacting realistically",
            "the subject moves forward with lifelike motion while the background shifts subtly with parallax",
            "small natural movements ripple through the scene - hair, fur, leaves and water responding to a soft breeze"};

    static String localTemplate(String idea, String target, String style, long seed) {
        Random r = new Random(seed);
        String subject = idea.isBlank() ? "a striking scene" : idea.replaceAll("[.!?]+$", "");
        String st = style == null || style.isBlank() ? "" : ", " + style + " style";
        String s;
        switch (target) {
            case "image":
                s = pick(r, SHOT) + " of " + subject + st + ". " + cap(pick(r, LIGHT)) + ", " + pick(r, LENS) + ". "
                        + "Highly detailed textures, balanced composition with strong depth, " + pick(r, GRADE) + ", " + pick(r, MOOD) + " atmosphere.";
                break;
            case "i2v":
                s = cap(subject) + ": " + pick(r, MOTION_I2V) + ". Camera: " + pick(r, CAM) + ". "
                        + "Smooth, continuous motion over five seconds, " + pick(r, LIGHT) + ", " + pick(r, MOOD) + " mood.";
                break;
            default:
                s = pick(r, SHOT) + " of " + subject + st + ", unfolding over five seconds with natural, fluid motion. "
                        + "Camera: " + pick(r, CAM) + ". " + cap(pick(r, LIGHT)) + ", " + pick(r, LENS) + ", " + pick(r, GRADE) + ". "
                        + cap(pick(r, MOOD)) + " mood, cinematic film look.";
        }
        return clean(s);
    }

    private static String pick(Random r, String[] a) { return a[r.nextInt(a.length)]; }
    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
