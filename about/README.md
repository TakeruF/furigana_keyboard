# Furigana Keyboard About site

Public product page for Furigana Keyboard. Until release URLs are configured,
the Android and iOS cards display a non-clickable “準備中” state.

The page is available in Japanese (`/ja`), Simplified Chinese (`/zh-Hans`),
Traditional Chinese (`/zh-Hant`), English (`/en`), and Korean (`/ko`). The root
URL redirects to the closest supported browser language, falling back to Japanese.
Simplified and Traditional Chinese use Noto Sans SC and TC respectively, while
Korean uses Noto Sans KR; each is applied only to its locale route.

## Local development

```bash
npm install
npm run dev
npm run build
```

The default scripts use standard Next.js and produce `.next/` for EdgeOne
Makers. The former Sites/Vinext build remains available as `npm run build:sites`
during the hosting transition.

## Release links

Set these environment variables in the hosting environment, then rebuild:

- `NEXT_PUBLIC_ANDROID_APK_URL`: direct APK or release-page URL
- `NEXT_PUBLIC_APP_STORE_URL`: Apple App Store product URL
No source edit is required when the release URLs become available.

## Publishing

See [`PUBLISHING.md`](./PUBLISHING.md) for the EdgeOne Makers deployment guide,
including coexistence with the existing `hanlu.app` project and the
`keyboard.hanlu.app` custom-domain setup.
