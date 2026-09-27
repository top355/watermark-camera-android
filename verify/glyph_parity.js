// 字形轮廓提取器（verify/glyph_out.py）的闭环验证。
//
// 自己写的 glyf 解析器最容易错在：隐含中点没补、loca 基址忘了加、y 没翻转、
// 复合字形没展开。这些错误"看起来还是汉字"，肉眼逐字比对根本发现不了。
// 所以这里不比字，比**像素**：把提取出来的路径画到 canvas 上，再把同一个字体
// 用 fillText 画一遍，两张图逐像素相减 —— 对得上才说明路径是那个字。
//
// 用法（两步，python 必须由外层 shell 调用，见下）：
//   python verify/gen_glyphs.py && node verify/glyph_parity.js
// 出图（人工看叠图）：
//   python verify/gen_glyphs.py && node verify/glyph_parity.js --png
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const FONT = "Y:/今日水印相机字体/AlimamaShuHeiTi-Bold.ttf";
const JSONF = path.join(__dirname, "_glyphs.json");

/* 这里刻意**不** spawn python：这台机器上 Node spawn 子进程会 EBUSY
   （README §"从 Node 里 spawn Chrome 会 EBUSY" 记过同一个坑）。
   轮廓由外层的 gen_glyphs.py 先落成 JSON，这里只读文件。 */
if (!fs.existsSync(JSONF)) throw new Error("先跑：python verify/gen_glyphs.py");
const data = JSON.parse(fs.readFileSync(JSONF, "utf8"));

/* 内联字体（data URI）—— 页面上要拿同一份字体做 fillText 对照。
   file:// 页面外链字体会被 CORS 拦掉，两侧都得走 data URI 才公平。 */
const b64 = fs.readFileSync(FONT).toString("base64");

const html = `<!doctype html><meta charset="utf-8">
<style>
@font-face{font-family:"AMS";src:url(data:font/ttf;base64,${b64}) format("truetype");font-display:block}
body{margin:0;background:#101216}
#log{color:#9aa4b2;font:13px/1.6 monospace;padding:8px 12px;white-space:pre-wrap}
</style>
<canvas id="a"></canvas><canvas id="b"></canvas>
<div id="log">running…</div>
<script>
const DATA = ${JSON.stringify(data)};
const EM = 1000;               // 1 em = 1000 px，字体单位直接用
const BASE = 1000;             // 基线放在 y=1000，上方留 900 给字身、下方 400 给降部
(async ()=>{
  await document.fonts.load(EM + 'px "AMS"');
  await document.fonts.ready;
  const lines = [];
  let bad = 0;

  for(const d of DATA){
    const W = Math.ceil(d.total), H = BASE + 400;
    const mk = () => { const c=document.createElement("canvas"); c.width=W; c.height=H; return c; };
    const ca = mk(), cb = mk();
    const xa = ca.getContext("2d"), xb = cb.getContext("2d");
    for(const x of [xa,xb]){ x.fillStyle="#000"; x.fillRect(0,0,W,H); x.fillStyle="#fff"; }

    // A：提取出来的路径
    xa.save(); xa.translate(0, BASE);
    for(const g of d.glyphs){
      if(!g.path) continue;
      xa.save(); xa.translate(g.x, 0);
      xa.fill(new Path2D(g.path));
      xa.restore();
    }
    xa.restore();

    // B：同一个字体直接排版（这就是"真值"）
    xb.font = EM + 'px "AMS"';
    xb.textBaseline = "alphabetic";
    xb.fillText(d.s, 0, BASE);

    const A = xa.getImageData(0,0,W,H).data, B = xb.getImageData(0,0,W,H).data;
    let diff = 0, ink = 0;
    for(let i=0;i<A.length;i+=4){
      const pa = A[i] > 127, pb = B[i] > 127;
      if(pb) ink++;
      if(pa !== pb) diff++;
    }
    const r = ink ? diff/ink : 1;
    // 抗锯齿边缘本来就会差几个像素；1.5% 以内算"同一个字"
    const pass = r < 0.015;
    if(!pass) bad++;
    lines.push((pass?"OK   ":"FAIL ") + d.s + "  像素差 " + diff + " / 墨迹 " + ink +
               " = " + (r*100).toFixed(3) + "%   (画布 " + W + "x" + H + ")");

    if(location.search.indexOf("png") >= 0){
      // 叠一张：绿=A 有、红=B 有、黄=重合 —— 错在哪一眼能看出来
      const ov = document.createElement("canvas"); ov.width=W; ov.height=H;
      const xo = ov.getContext("2d");
      const img = xo.createImageData(W,H);
      for(let i=0;i<A.length;i+=4){
        const pa=A[i]>127, pb=B[i]>127;
        img.data[i]   = pb?255:0;
        img.data[i+1] = pa?255:0;
        img.data[i+2] = 0; img.data[i+3] = 255;
      }
      xo.putImageData(img,0,0);
      ov.style.cssText = "display:block;width:" + (W/2) + "px;margin:4px 0";
      document.body.appendChild(ov);
    }
  }
  document.getElementById("log").textContent =
    lines.join("\\n") + "\\n\\n" + (bad ? bad + " 处轮廓与字体不一致" : "全部轮廓与字体逐像素一致");
})();
</script>`;

const tmp = path.join(root, "verify", "_glyph_parity.html");
fs.writeFileSync(tmp, html);
console.log("wrote verify/_glyph_parity.html"
  + (process.argv.includes("--png") ? " (png 模式：用 ?png 打开)" : ""));
