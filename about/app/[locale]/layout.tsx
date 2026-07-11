import type { Metadata } from "next";
import { Noto_Sans_KR, Noto_Sans_SC, Noto_Sans_TC } from "next/font/google";
import { copy, isLocale, locales, type Locale } from "../i18n";
import "../globals.css";

const notoSansSC = Noto_Sans_SC({
  weight: "variable",
  display: "swap",
  preload: false,
  variable: "--font-noto-sans-sc",
  fallback: ["PingFang SC", "Microsoft YaHei", "sans-serif"],
});

const notoSansKR = Noto_Sans_KR({
  weight: "variable",
  display: "swap",
  preload: false,
  variable: "--font-noto-sans-kr",
  fallback: ["Apple SD Gothic Neo", "Malgun Gothic", "sans-serif"],
});

const notoSansTC = Noto_Sans_TC({
  weight: "variable",
  display: "swap",
  preload: false,
  variable: "--font-noto-sans-tc",
  fallback: ["PingFang TC", "Microsoft JhengHei", "sans-serif"],
});

type LayoutProps = Readonly<{
  children: React.ReactNode;
  params: Promise<{ locale: string }>;
}>;

function getLocale(value: string): Locale {
  return isLocale(value) ? value : "ja";
}

export function generateStaticParams() {
  return locales.map((locale) => ({ locale }));
}

export async function generateMetadata({ params }: Pick<LayoutProps, "params">): Promise<Metadata> {
  const { locale: value } = await params;
  const locale = getLocale(value);
  const content = copy[locale];
  const base = new URL(process.env.NEXT_PUBLIC_SITE_URL ?? "https://keyboard.hanlu.app");

  return {
    metadataBase: base,
    title: content.meta.title,
    description: content.meta.description,
    icons: {
      icon: [{ url: "/app-icon.png", type: "image/png" }],
      apple: "/app-icon.png",
    },
    alternates: {
      canonical: `/${locale}`,
      languages: Object.fromEntries(locales.map((item) => [copy[item].htmlLang, `/${item}`])),
    },
    openGraph: {
      title: "Furigana Keyboard",
      description: content.meta.social,
      locale: content.htmlLang.replace("-", "_"),
      type: "website",
      images: [{ url: new URL("/og.png", base).toString(), width: 1200, height: 630, alt: "Furigana Keyboard" }],
    },
    twitter: {
      card: "summary_large_image",
      title: "Furigana Keyboard",
      description: content.meta.social,
      images: [new URL("/og.png", base).toString()],
    },
  };
}

export default async function LocaleLayout({ children, params }: LayoutProps) {
  const { locale: value } = await params;
  const locale = getLocale(value);
  const localeFontClass = locale === "zh-Hans"
    ? notoSansSC.variable
    : locale === "zh-Hant"
      ? notoSansTC.variable
    : locale === "ko"
      ? notoSansKR.variable
      : undefined;

  return (
    <html lang={copy[locale].htmlLang}>
      <body className={localeFontClass}>{children}</body>
    </html>
  );
}
