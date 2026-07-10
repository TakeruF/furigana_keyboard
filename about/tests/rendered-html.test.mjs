import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import test from "node:test";

async function render(path = "/ja") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}-${path}`);
  const { default: worker } = await import(workerUrl.href);
  return worker.fetch(
    new Request(`http://localhost${path}`, { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("redirects the root page to Japanese", async () => {
  const response = await render("/");
  assert.equal(response.status, 307);
  assert.equal(response.headers.get("location"), "http://localhost/ja");
});

for (const expectation of [
  { path: "/ja", lang: "ja", title: "書く。読める。", pending: "準備中" },
  { path: "/zh", lang: "zh-CN", title: "书写。读懂。", pending: "准备中" },
  { path: "/en", lang: "en", title: "Write. Read.", pending: "Coming soon" },
  { path: "/ko", lang: "ko", title: "쓰고. 읽고.", pending: "준비 중" },
]) {
  test(`server-renders localized content for ${expectation.path}`, async () => {
    const response = await render(expectation.path);
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);
    const html = await response.text();
    assert.match(html, new RegExp(`<html lang="${expectation.lang}">`));
    assert.match(html, new RegExp(expectation.title.replaceAll(".", "\\.")));
    assert.match(html, new RegExp(expectation.pending));
    assert.match(html, /href="\/ja"/);
    assert.match(html, /href="\/zh"/);
    assert.match(html, /href="\/en"/);
    assert.match(html, /href="\/ko"/);
    assert.match(html, /mailto:support@hanlu\.app/);
    assert.doesNotMatch(html, /github\.com\/TakeruF|furigana_keyboard/);
    assert.doesNotMatch(html, /codex-preview|react-loading-skeleton/);
  });
}

test("keeps future release URLs configurable without source rewrites", async () => {
  const page = await readFile(new URL("../app/[locale]/page.tsx", import.meta.url), "utf8");
  assert.match(page, /NEXT_PUBLIC_ANDROID_APK_URL/);
  assert.match(page, /NEXT_PUBLIC_APP_STORE_URL/);
  assert.doesNotMatch(page, /NEXT_PUBLIC_SOURCE_URL|github\.com\/TakeruF/);
  await access(new URL("../public/og.png", import.meta.url));
});

test("assigns dedicated Noto fonts to Chinese and Korean", async () => {
  const [layout, styles] = await Promise.all([
    readFile(new URL("../app/[locale]/layout.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
  ]);
  assert.match(layout, /Noto_Sans_SC/);
  assert.match(layout, /Noto_Sans_KR/);
  assert.match(layout, /locale === "zh"[\s\S]*notoSansSC\.variable/);
  assert.match(layout, /locale === "ko"[\s\S]*notoSansKR\.variable/);
  assert.match(styles, /\[lang="zh-CN"\] body[\s\S]*var\(--font-noto-sans-sc\)/);
  assert.match(styles, /\[lang="ko"\] body[\s\S]*var\(--font-noto-sans-kr\)/);
});
