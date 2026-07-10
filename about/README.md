# Furigana Keyboard About site

Public product page for Furigana Keyboard. Until release URLs are configured,
the Android and iOS cards display a non-clickable “準備中” state.

The page is available in Japanese (`/ja`), Simplified Chinese (`/zh`), English
(`/en`), and Korean (`/ko`). The root URL redirects to Japanese.
Simplified Chinese uses the self-hosted Noto Sans SC web font, while Korean uses
the self-hosted Noto Sans KR web font; each is applied only to its locale route.

## Local development

```bash
npm install
npm run dev
npm run build
```

## Release links

Set these environment variables in the hosting environment, then rebuild:

- `NEXT_PUBLIC_ANDROID_APK_URL`: direct APK or release-page URL
- `NEXT_PUBLIC_APP_STORE_URL`: Apple App Store product URL
No source edit is required when the release URLs become available.

## Publishing

See [`PUBLISHING.md`](./PUBLISHING.md) for the complete OpenAI Sites deployment,
public-access, and `keyboard.hanlu.app` custom-domain setup guide.
