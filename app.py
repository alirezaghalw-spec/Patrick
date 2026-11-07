import os, json, io, uuid, subprocess, tempfile, sys
from flask import Flask, request, Response, jsonify
from openai import OpenAI
from PIL import Image, ImageOps, ImageFilter
from cachetools import TTLCache  # pip install cachetools

# ---------- پیکربندی ----------

app = Flask(__name__, static_folder="static", static_url_path="")
app.config["MAX_CONTENT_LENGTH"] = 10 * 1024 * 1024  # حداکثر ۱۰ مگابایت

_api_key = os.environ.get("OPENROUTER_API_KEY")
if not _api_key:
    raise RuntimeError("متغیر محیطی OPENROUTER_API_KEY تنظیم نشده است")

client = OpenAI(
    api_key=_api_key,
    base_url="https://openrouter.ai/api/v1",
)

MODELS = [
    {"id": "deepseek/deepseek-chat:free", "name": "DeepSeek V3", "free": True},
    {"id": "openai/gpt-4o-mini", "name": "GPT-4o mini", "free": False},
    {"id": "openai/gpt-4o", "name": "GPT-4o", "free": False},
    {"id": "deepseek/deepseek-chat", "name": "DeepSeek V3 (پولی)", "free": False},
]
ALLOWED_MODELS = {m["id"] for m in MODELS}

IMAGES = TTLCache(maxsize=100, ttl=3600)
Image.MAX_IMAGE_PIXELS = 50_000_000
MAX_OUTPUT_IMAGE_BYTES = 5 * 1024 * 1024

# ---------- بارگذاری tools.json ----------

TOOLS_CONFIG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tools.json")
try:
    with open(TOOLS_CONFIG_PATH, encoding="utf-8") as f:
        PACKAGES = json.load(f).get("tools", [])
except FileNotFoundError:
    PACKAGES = []

# فقط پکیج‌های نوع pip (چون فقط همین‌ها در sandbox نصب می‌شن)
PIP_PACKAGES = [p["package"] for p in PACKAGES if p.get("type") == "pip" and "package" in p]
_PKG_LIST = "، ".join(PIP_PACKAGES) if PIP_PACKAGES else "هیچ"
_PKG_HINT = (
    f"پکیج‌های نصب‌شده در محیط اجرا: {_PKG_LIST}. "
    "هیچ پکیج دیگری نصب نیست و دسترسی به pip install وجود ندارد. "
    "اگر پکیجی لازم بود که موجود نیست، به کاربر بگویید."
)

SYSTEM = (
    "تو یک دستیار برنامه‌نویسی و ویرایش تصویر هستی و به فارسی پاسخ می‌دهی. "
    "برای اجرای کد از ابزار run_python و برای ویرایش عکس از ابزار edit_image استفاده کن. "
    + _PKG_HINT
)

# ---------- ابزارها ----------

def run_python(code: str) -> str:
    """اجرای کد پایتون با محدودیت زمان و منابع.

    توجه: sandbox کامل نیست. برای استفاده‌ی عمومی سرویس را در
    کانتینر با --network=none و --memory محدود اجرا کنید.
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        path = os.path.join(tmpdir, "user_code.py")
        with open(path, "w", encoding="utf-8") as f:
            f.write(code)
        try:
            # sys.executable برای اطمینان از پیدا شدن پایتون حتی با env={}
            p = subprocess.run(
                [
                    "sh", "-c",
                    "ulimit -t 5 2>/dev/null; ulimit -f 10240 2>/dev/null; "
                    'exec "$1" -I -B "$2"',
                    "_", sys.executable, path,
                ],
                capture_output=True, text=True, timeout=10,
                env={
                    "PATH": "/usr/local/bin:/usr/bin:/bin",
                    "HOME": tmpdir,
                    "LANG": "C.UTF-8",
                    "PYTHONIOENCODING": "utf-8",
                },
                cwd=tmpdir,
            )
            out = (p.stdout or "") + (p.stderr or "")
            return out[:4000] or "بدون خروجی"
        except subprocess.TimeoutExpired:
            return "زمان اجرا تمام شد (بیش از ۵ ثانیه)"
        except Exception as e:
            return f"خطا در اجرا: {e}"


def edit_image(image_id: str, operation: str, value: str = "") -> str:
    """عملیات: grayscale، resize (مثلاً 800x600)، rotate (زاویه)، blur (شدت)."""
    raw = IMAGES.get(image_id)
    if raw is None:
        return "تصویری با این شناسه پیدا نشد (ممکن است منقضی شده باشد)"
    try:
        im = Image.open(io.BytesIO(raw))
        im.load()
    except Exception as e:
        return f"خطا در باز کردن تصویر: {e}"

    try:
        if operation == "grayscale":
            im = ImageOps.grayscale(im)
        elif operation == "resize":
            try:
                w_str, h_str = value.lower().split("x")
                w, h = int(w_str), int(h_str)
            except Exception:
                return "فرمت ابعاد باید مثل 800x600 باشد"
            if not (1 <= w <= 10000 and 1 <= h <= 10000):
                return "ابعاد باید بین 1 و 10000 باشد"
            im = im.resize((w, h), Image.LANCZOS)
        elif operation == "rotate":
            try:
                angle = float(value or 90)
            except ValueError:
                return "زاویه باید عدد باشد"
            im = im.rotate(angle, expand=True)
        elif operation == "blur":
            try:
                radius = float(value or 2)
            except ValueError:
                return "شدت بلور باید عدد باشد"
            if not (0 <= radius <= 50):
                return "شدت بلور باید بین 0 و 50 باشد"
            im = im.filter(ImageFilter.GaussianBlur(radius))
        else:
            return "عملیات نامعتبر است"

        if im.mode not in ("RGB", "RGBA", "L", "LA"):
            im = im.convert("RGBA" if im.mode in ("P", "PA") else "RGB")

        out = io.BytesIO()
        im.save(out, format="PNG")
        data = out.getvalue()
        if len(data) > MAX_OUTPUT_IMAGE_BYTES:
            return "تصویر خروجی بیش از حد بزرگ است (بیش از ۵ مگابایت)"
    except Exception as e:
        return f"خطا در پردازش تصویر: {e}"

    new_id = str(uuid.uuid4())
    IMAGES[new_id] = data
    return f"تصویر جدید ساخته شد. شناسه: {new_id}"


TOOL_FUNCS = {"run_python": run_python, "edit_image": edit_image}

TOOLS = [
    {"type": "function", "function": {
        "name": "run_python",
        "description": "اجرای کد پایتون و برگرداندن خروجی. " + _PKG_HINT,
        "parameters": {"type": "object", "properties": {
            "code": {"type": "string", "description": "کد پایتون"}},
            "required": ["code"]}}},
    {"type": "function", "function": {
        "name": "edit_image",
        "description": "ویرایش تصویر آپلودشده با شناسه مشخص",
        "parameters": {"type": "object", "properties": {
            "image_id": {"type": "string"},
            "operation": {
                "type": "string",
                "enum": ["grayscale", "resize", "rotate", "blur"],
            },
            "value": {"type": "string"}},
            "required": ["image_id", "operation"]}}},
]

# ---------- مسیرها ----------

@app.errorhandler(413)
def too_large(e):
    return jsonify(error="حجم فایل بیش از ۱۰ مگابایت است"), 413


@app.route("/models")
def models():
    return jsonify(MODELS)


@app.route("/packages")
def packages():
    return jsonify(PACKAGES)


@app.route("/upload", methods=["POST"])
def upload():
    f = request.files.get("image")
    if not f:
        return jsonify(error="فایل تصویر ارسال نشده (کلید: image)"), 400
    raw = f.read()
    if not raw:
        return jsonify(error="فایل خالی است"), 400
    try:
        im = Image.open(io.BytesIO(raw))
        im.verify()
    except Exception:
        return jsonify(error="فایل تصویر معتبر نیست"), 400

    img_id = str(uuid.uuid4())
    IMAGES[img_id] = raw
    return jsonify(id=img_id)


@app.route("/chat", methods=["POST"])
def chat():
    data = request.get_json(silent=True) or {}
    model_id = data.get("model")
    if model_id not in ALLOWED_MODELS:
        return jsonify(error="مدل نامعتبر است"), 400

    user_msgs = data.get("messages") or []
    if not isinstance(user_msgs, list):
        return jsonify(error="messages باید لیست باشد"), 400

    safe_msgs = []
    for m in user_msgs:
        if not isinstance(m, dict):
            continue
        role = m.get("role")
        content = m.get("content")
        if role not in ("user", "assistant"):
            continue
        if not isinstance(content, str):
            continue
        safe_msgs.append({"role": role, "content": content})

    def generate():
        msgs = [{"role": "system", "content": SYSTEM}] + safe_msgs
        try:
            for _ in range(5):
                stream = client.chat.completions.create(
                    model=model_id, messages=msgs, tools=TOOLS, stream=True
                )
                text, calls = "", {}
                for chunk in stream:
                    if not chunk.choices:
                        continue
                    delta = chunk.choices[0].delta
                    if delta.content:
                        text += delta.content
                        yield (
                            "data: "
                            + json.dumps(
                                {"type": "text", "content": delta.content},
                                ensure_ascii=False,
                            )
                            + "\n\n"
                        )
                    for tc in (delta.tool_calls or []):
                        idx = getattr(tc, "index", 0)
                        if idx is None:
                            idx = 0
                        c = calls.setdefault(
                            idx, {"id": "", "name": "", "args": ""}
                        )
                        if tc.id:
                            c["id"] = tc.id
                        if tc.function and tc.function.name:
                            c["name"] += tc.function.name
                        if tc.function and tc.function.arguments:
                            c["args"] += tc.function.arguments

                if not calls:
                    break

                for c in calls.values():
                    if not c["id"]:
                        c["id"] = f"call_{uuid.uuid4().hex[:24]}"

                msgs.append({
                    "role": "assistant",
                    "content": text or None,
                    "tool_calls": [
                        {"id": c["id"], "type": "function",
                         "function": {"name": c["name"], "arguments": c["args"]}}
                        for c in calls.values()
                    ],
                })

                for c in calls.values():
                    yield (
                        "data: "
                        + json.dumps(
                            {"type": "tool", "name": c["name"]},
                            ensure_ascii=False,
                        )
                        + "\n\n"
                    )
                    fn = TOOL_FUNCS.get(c["name"])
                    if fn is None:
                        result = f"ابزار ناشناخته: {c['name']}"
                    else:
                        try:
                            args = json.loads(c["args"] or "{}")
                            if not isinstance(args, dict):
                                raise ValueError("آرگومان‌ها باید object باشند")
                            result = fn(**args)
                        except json.JSONDecodeError as e:
                            result = f"خطا در JSON آرگومان‌ها: {e}"
                        except Exception as e:
                            result = f"خطا: {e}"
                    msgs.append({
                        "role": "tool",
                        "tool_call_id": c["id"],
                        "content": str(result),
                    })
            else:
                yield (
                    "data: "
                    + json.dumps(
                        {"type": "text",
                         "content": "به سقف تعداد گام‌های ابزار رسیدم."},
                        ensure_ascii=False,
                    )
                    + "\n\n"
                )
        except Exception as e:
            yield (
                "data: "
                + json.dumps(
                    {"type": "error", "content": str(e)},
                    ensure_ascii=False,
                )
                + "\n\n"
            )
        yield f"data: {json.dumps({'type': 'done'})}\n\n"

    return Response(
        generate(),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",
            "Connection": "keep-alive",
        },
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, threaded=True)
