import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import { after, before, test } from "node:test";
import { spawn } from "node:child_process";
import net from "node:net";

let server;
let baseUrl;

async function availablePort() {
  return new Promise((resolve, reject) => {
    const listener = net.createServer();
    listener.once("error", reject);
    listener.listen(0, "127.0.0.1", () => {
      const address = listener.address();
      listener.close(() => resolve(address.port));
    });
  });
}

async function waitForServer(url) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      await fetch(url, { redirect: "manual" });
      return;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 150));
    }
  }
  throw new Error(`Next.js test server did not start at ${url}`);
}

before(async () => {
  const port = await availablePort();
  baseUrl = `http://127.0.0.1:${port}`;
  server = spawn(
    process.execPath,
    ["node_modules/next/dist/bin/next", "start", "-H", "127.0.0.1", "-p", String(port)],
    { cwd: new URL("..", import.meta.url), stdio: "ignore" },
  );
  await waitForServer(baseUrl);
});

after(() => {
  server?.kill("SIGTERM");
});

test("redirects the root page to Japanese", async () => {
  const response = await fetch(baseUrl, { redirect: "manual" });
  assert.equal(response.status, 307);
  assert.equal(response.headers.get("location"), "/ja");
});

for (const expectation of [
  { path: "/ja", lang: "ja", title: "書く。読める。", pending: "準備中" },
  { path: "/zh", lang: "zh-CN", title: "书写。读懂。", pending: "准备中" },
  { path: "/en", lang: "en", title: "Write. Read.", pending: "Coming soon" },
  { path: "/ko", lang: "ko", title: "쓰고. 읽고.", pending: "준비 중" },
]) {
  test(`server-renders localized content for ${expectation.path}`, async () => {
    const response = await fetch(`${baseUrl}${expectation.path}`);
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
    assert.match(html, new RegExp(`href="${expectation.path}/terms"`));
    assert.match(html, new RegExp(`href="${expectation.path}/privacy"`));
    assert.match(html, /keyboard-preview\.jpg/);
    assert.match(html, /app-icon\.png/);
    assert.match(html, /google-play-glyph/);
    assert.match(html, /apple-glyph/);
    assert.match(html, /https:\/\/downloads\.hanlu\.app\/furigana-keyboard\/v1\.0\.0-beta\.2\.apk/);
    assert.doesNotMatch(html, /Zinnia|Tegaki|KANJIDIC2|JMdict/);
    assert.doesNotMatch(html, /github\.com\/TakeruF|furigana_keyboard/);
    assert.doesNotMatch(html, /codex-preview|react-loading-skeleton/);
  });
}

for (const locale of ["ja", "zh", "en", "ko"]) {
  for (const document of ["terms", "privacy"]) {
    test(`serves ${locale} ${document} as a subpage`, async () => {
      const response = await fetch(`${baseUrl}/${locale}/${document}`);
      assert.equal(response.status, 200);
      const html = await response.text();
      assert.match(html, /support@hanlu\.app/);
      assert.match(html, new RegExp(`href="/${locale}"`));
    });
  }
}

test("keeps future release URLs configurable without source rewrites", async () => {
  const page = await readFile(new URL("../app/[locale]/page.tsx", import.meta.url), "utf8");
  assert.match(page, /NEXT_PUBLIC_ANDROID_APK_URL/);
  assert.match(page, /NEXT_PUBLIC_APP_STORE_URL/);
  assert.doesNotMatch(page, /NEXT_PUBLIC_SOURCE_URL|github\.com\/TakeruF/);
  assert.doesNotMatch(page, /content\.download\.note|release-note/);
  await access(new URL("../public/og.png", import.meta.url));
  await access(new URL("../public/keyboard-preview.jpg", import.meta.url));
  await access(new URL("../public/app-icon.png", import.meta.url));
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
