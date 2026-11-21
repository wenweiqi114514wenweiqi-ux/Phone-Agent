import { FilePreviewer } from "@dukeofwest/universal-file-preview";

/**
 * Android WebView 容器入口。
 * 文件由 Kotlin 端通过 WebViewAssetLoader 挂在 /files/<token> 下，
 * 这里只负责取字节、初始化查看器、把状态回传原生。
 */
const container = document.getElementById("viewer");
const statusEl = document.getElementById("status");
const metaEl = document.getElementById("meta");
let preview = null;

function notify(type, text) {
  try {
    if (window.PhiBridge && window.PhiBridge.onEvent) window.PhiBridge.onEvent(type, text || "");
  } catch (e) { /* 原生端未接入时忽略 */ }
}

function setStatus(text) {
  if (!statusEl) return;
  statusEl.textContent = text || "";
  statusEl.style.display = text ? "block" : "none";
}

function params() {
  const q = new URLSearchParams(location.search);
  return {
    token: q.get("f") || "",
    name: q.get("name") || "document",
    mime: q.get("mime") || "",
  };
}

async function openDocument() {
  const p = params();
  if (metaEl) metaEl.textContent = p.name;
  if (!p.token) {
    setStatus("缺少文件参数");
    notify("error", "缺少文件参数");
    return;
  }
  setStatus("正在解析…");
  notify("loading", p.name);
  try {
    // 必须用绝对路径 /files/：页面本身在 /assets/office/ 下，相对路径会变成 /assets/office/files/... 而 404
    const res = await fetch("/files/" + encodeURIComponent(p.token), { cache: "no-store" });
    if (!res.ok) throw new Error("读取文件失败：HTTP " + res.status);
    const bytes = await res.arrayBuffer();

    preview = new FilePreviewer({
      container,
      file: {
        name: p.name,
        source: bytes,
        mimeType: p.mime || guessMime(p.name),
      },
      // 工具栏交给原生顶栏，容器内只留内容
      toolbar: false,
      zoom: false,
      download: false,
    });
    await preview.open();
    setStatus("");
    notify("ready", p.name);
  } catch (error) {
    const msg = (error && (error.message || String(error))) || "未知错误";
    setStatus(msg);
    notify("error", msg);
  }
}

function guessMime(name) {
  const ext = (name.split(".").pop() || "").toLowerCase();
  switch (ext) {
    case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
    case "odt": return "application/vnd.oasis.opendocument.text";
    case "odp": return "application/vnd.oasis.opendocument.presentation";
    default: return "";
  }
}

// 原生调用：缩放 / 适应宽度
window.PhiOffice = {
  fitWidth() { try { preview && preview.fitToWidth(); } catch (e) {} },
  fitPage() { try { preview && preview.fitToPage(); } catch (e) {} },
  zoom(v) { try { preview && preview.setZoom(Number(v) || 1); } catch (e) {} },
  destroy() { try { preview && preview.destroy(); } catch (e) {} },
};

window.addEventListener("load", openDocument);
window.addEventListener("unhandledrejection", (e) => {
  const msg = (e.reason && (e.reason.message || String(e.reason))) || "未知错误";
  setStatus(msg);
  notify("error", msg);
});
