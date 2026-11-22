import * as XLSX from "xlsx";

/**
 * 表格预览：xls / xlsx / ods / csv 共用一套页面。
 *
 * 文件字节由原生 WebViewAssetLoader 挂在 /files/<token>，不经过上传。
 * 只渲染前 2000 行 / 100 列，避免超大表格把 WebView 卡死。
 *
 * CSV 与二进制表格走同一个渲染器，保证颜色、页签、网格、
 * 行数统计和“表格到内容为止”的行为完全一致。
 */
const MAX_ROWS = 2000;
const MAX_COLS = 100;

const container = document.getElementById("viewer");
const tabsEl = document.getElementById("tabs");
const statusEl = document.getElementById("status");
const metaEl = document.getElementById("meta");
const docStatusEl = document.getElementById("doc-status");

function setStatus(text) {
  if (!statusEl) return;
  statusEl.textContent = text || "";
  statusEl.style.display = text ? "block" : "none";
}

function escapeHtml(v) {
  return String(v == null ? "" : v)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function params() {
  const q = new URLSearchParams(location.search);
  return {
    token: q.get("f") || "",
    name: q.get("name") || "表格",
    mode: q.get("mode") || "",
    bg: q.get("bg") || "FFFFFF",
    fg: q.get("fg") || "111111",
  };
}

function cellKey(r, c) {
  return r + ":" + c;
}

function sheetKind(p) {
  if (p.mode === "csv") return "CSV";
  const ext = (p.name.split(".").pop() || "").toUpperCase();
  return ext || "表格";
}

function renderSheet(sheet) {
  const ref = sheet["!ref"];
  if (!ref) return null;
  const range = XLSX.utils.decode_range(ref);
  const endRow = Math.min(range.e.r, range.s.r + MAX_ROWS - 1);
  const endCol = Math.min(range.e.c, range.s.c + MAX_COLS - 1);
  const clipped = endRow < range.e.r || endCol < range.e.c;

  const rows = XLSX.utils.sheet_to_json(sheet, {
    header: 1,
    raw: false,
    defval: "",
    range: { s: { r: range.s.r, c: range.s.c }, e: { r: endRow, c: endCol } },
  });

  const mergeMap = new Map();
  const covered = new Set();
  (sheet["!merges"] || []).forEach((m) => {
    if (m.s.r > endRow || m.s.c > endCol) return;
    const e = { r: Math.min(m.e.r, endRow), c: Math.min(m.e.c, endCol) };
    mergeMap.set(cellKey(m.s.r - range.s.r, m.s.c - range.s.c), {
      rowspan: e.r - m.s.r + 1,
      colspan: e.c - m.s.c + 1,
    });
    for (let r = m.s.r; r <= e.r; r++) {
      for (let c = m.s.c; c <= e.c; c++) {
        if (r !== m.s.r || c !== m.s.c) covered.add(cellKey(r - range.s.r, c - range.s.c));
      }
    }
  });

  const parts = [];
  parts.push('<div class="table-wrap"><table>');
  parts.push('<thead><tr><th class="corner"></th>');
  for (let c = range.s.c; c <= endCol; c++) {
    parts.push('<th class="colh">' + escapeHtml(XLSX.utils.encode_col(c)) + "</th>");
  }
  parts.push("</tr></thead><tbody>");

  for (let r = 0; r < rows.length; r++) {
    parts.push('<tr><th class="rownum">' + (range.s.r + r + 1) + "</th>");
    const row = rows[r] || [];
    for (let c = 0; c <= endCol - range.s.c; c++) {
      const key = cellKey(r, c);
      if (covered.has(key)) continue;
      const span = mergeMap.get(key);
      const attrs = span
        ? ' rowspan="' + span.rowspan + '" colspan="' + span.colspan + '"'
        : "";
      const val = row[c];
      parts.push("<td" + attrs + ">" + escapeHtml(val) + "</td>");
    }
    parts.push("</tr>");
  }
  parts.push("</tbody></table></div>");

  return {
    html: parts.join(""),
    rows: rows.length,
    cols: endCol - range.s.c + 1,
    clipped,
  };
}

function showSheet(sheets, index) {
  const sheet = sheets[index];
  if (!sheet) return;
  tabsEl.querySelectorAll(".tab").forEach((tab, i) => {
    tab.setAttribute("aria-selected", i === index ? "true" : "false");
  });
  const clip = sheet.clipped
    ? "已截断到 " + MAX_ROWS + " 行 / " + MAX_COLS + " 列"
    : "可左右滑动";
  container.innerHTML =
    '<div class="table-meta"><span class="grow">' +
    sheet.rows +
    " 行 · " +
    sheet.cols +
    ' 列</span><span class="clip">' +
    clip +
    "</span></div>" +
    sheet.html;
}

async function openWorkbook() {
  const p = params();
  document.documentElement.style.setProperty("--bg", "#" + p.bg.replace("#", ""));
  document.documentElement.style.setProperty("--fg", "#" + p.fg.replace("#", ""));
  if (metaEl) metaEl.textContent = p.name;
  if (docStatusEl) docStatusEl.textContent = p.name + " - " + sheetKind(p);
  if (!p.token) {
    setStatus("缺少文件参数");
    return;
  }
  setStatus("正在解析表格…");
  try {
    const res = await fetch("/files/" + encodeURIComponent(p.token), { cache: "no-store" });
    if (!res.ok) throw new Error("读取文件失败：HTTP " + res.status);
    const bytes = new Uint8Array(await res.arrayBuffer());
    const isCsv = p.mode === "csv" || /\.csv$/i.test(p.name);

    let wb;
    if (isCsv) {
      let text;
      try {
        text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      } catch {
        text = new TextDecoder("gb18030").decode(bytes);
      }
      text = text.replace(/^\uFEFF/, "");
      wb = XLSX.read(text, { type: "string", cellDates: true, cellNF: true, raw: false });
    } else {
      wb = XLSX.read(bytes, { type: "array", cellDates: true, cellNF: true });
    }

    if (!wb.SheetNames || wb.SheetNames.length === 0) {
      throw new Error("工作簿里没有工作表");
    }

    const sheets = wb.SheetNames.map((name, i) => {
      const sheet = wb.Sheets[name];
      if (!sheet) return null;
      const displayName =
        isCsv && wb.SheetNames.length === 1
          ? p.name.replace(/\.csv$/i, "") || "CSV"
          : name;
      const rendered = renderSheet(sheet);
      if (!rendered) return null;
      return { name: displayName || "Sheet" + (i + 1), ...rendered };
    }).filter(Boolean);

    if (!sheets.length) throw new Error("这个工作表是空的");

    tabsEl.innerHTML = sheets
      .map(
        (sheet, i) =>
          '<button type="button" class="tab" data-index="' +
          i +
          '" aria-selected="' +
          (i === 0 ? "true" : "false") +
          '">' +
          escapeHtml(sheet.name) +
          "</button>"
      )
      .join("");
    tabsEl.querySelectorAll(".tab").forEach((tab, i) => {
      tab.addEventListener("click", () => showSheet(sheets, i));
    });
    showSheet(sheets, 0);
    setStatus("");
  } catch (error) {
    if (tabsEl) tabsEl.innerHTML = "";
    if (container) container.innerHTML = "";
    const msg = (error && (error.message || String(error))) || "未知错误";
    setStatus(msg);
  }
}

window.addEventListener("load", openWorkbook);
