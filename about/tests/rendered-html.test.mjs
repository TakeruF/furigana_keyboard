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

for (const languagePreference of [
  { header: "zh-CN,zh;q=0.9,en;q=0.8", locale: "zh-Hans" },
  { header: "zh-Hans-CN,zh;q=0.9", locale: "zh-Hans" },
  { header: "zh-TW,zh;q=0.9,en;q=0.8", locale: "zh-Hant" },
  { header: "zh-Hant-HK,zh;q=0.9", locale: "zh-Hant" },
  { header: "ko-KR,ko;q=0.9", locale: "ko" },
  { header: "en-US,en;q=0.9", locale: "en" },
  { header: "ja-JP,ja;q=0.9", locale: "ja" },
  { header: "fr-FR,fr;q=0.9", locale: "ja" },
]) {
  test(`redirects system language ${languagePreference.header} to ${languagePreference.locale}`, async () => {
    const response = await fetch(baseUrl, {
      redirect: "manual",
      headers: { "accept-language": languagePreference.header },
    });
    assert.equal(response.status, 307);
    assert.equal(response.headers.get("location"), `/${languagePreference.locale}`);
  });
}

for (const expectation of [
  { path: "/ja", lang: "ja", title: "書く。読める。", pending: "準備中" },
  { path: "/zh-Hans", lang: "zh-Hans", title: "书写。读懂。", pending: "准备中" },
  { path: "/zh-Hant", lang: "zh-Hant", title: "書寫。讀懂。", pending: "準備中" },
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
    assert.match(html, /Furigana Keyboard Beta/);
    assert.match(html, /href="\/ja"/);
    assert.match(html, /href="\/zh-Hans"/);
    assert.match(html, /href="\/zh-Hant"/);
    assert.match(html, /href="\/en"/);
    assert.match(html, /href="\/ko"/);
    assert.match(html, /mailto:support@hanlu\.app/);
    assert.doesNotMatch(html, />support@hanlu\.app</);
    assert.match(html, new RegExp(`href="${expectation.path}/terms"`));
    assert.match(html, new RegExp(`href="${expectation.path}/privacy"`));
    assert.match(html, /keyboard-preview\.jpg/);
    assert.match(html, /app-icon\.png/);
    assert.match(html, /class="site-header"/);
    assert.match(html, /class="hero-download primary"/);
    assert.match(html, /class="site-footer"/);
    assert.match(html, /class="footer-languages"/);
    assert.doesNotMatch(html, /class="header-languages"/);
    assert.match(html, /android-glyph/);
    assert.doesNotMatch(html, /google-play-glyph/);
    assert.match(html, /apple-glyph/);
    assert.match(
      html,
      /https:\/\/downloads\.hanlu\.app\/furigana-keyboard\/1\.0\.0-rc\.4\.apk/,
    );
    assert.doesNotMatch(html, /Zinnia|Tegaki|KANJIDIC2|JMdict/);
    if (expectation.path === "/ja") {
      assert.match(html, />ダウンロード</);
      assert.doesNotMatch(html, /入手する/);
    }
    assert.doesNotMatch(html, /github\.com\/TakeruF|furigana_keyboard/);
    assert.doesNotMatch(html, /codex-preview|react-loading-skeleton/);
  });
}

for (const locale of ["ja", "zh-Hans", "zh-Hant", "en", "ko"]) {
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
  const [page, links] = await Promise.all([
    readFile(new URL("../app/[locale]/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/release-links.ts", import.meta.url), "utf8"),
  ]);
  assert.match(links, /NEXT_PUBLIC_ANDROID_APK_URL/);
  assert.match(links, /NEXT_PUBLIC_APP_STORE_URL/);
  assert.doesNotMatch(page + links, /NEXT_PUBLIC_SOURCE_URL|github\.com\/TakeruF/);
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
  assert.match(layout, /Noto_Sans_TC/);
  assert.match(layout, /Noto_Sans_KR/);
  assert.match(layout, /locale === "zh-Hans"[\s\S]*notoSansSC\.variable/);
  assert.match(layout, /locale === "zh-Hant"[\s\S]*notoSansTC\.variable/);
  assert.match(layout, /locale === "ko"[\s\S]*notoSansKR\.variable/);
  assert.match(styles, /\[lang="zh-Hans"\] body[\s\S]*var\(--font-noto-sans-sc\)/);
  assert.match(styles, /\[lang="zh-Hant"\] body[\s\S]*var\(--font-noto-sans-tc\)/);
  assert.match(styles, /\[lang="ko"\] body[\s\S]*var\(--font-noto-sans-kr\)/);
});
