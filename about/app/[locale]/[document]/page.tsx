import type { Metadata } from "next";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { notFound } from "next/navigation";
import { copy, isLocale, locales, type Locale } from "../../i18n";
import SiteFooter from "../../components/SiteFooter";
import SiteHeader from "../../components/SiteHeader";

const documents = ["terms", "privacy"] as const;
type LegalDocument = (typeof documents)[number];

const localeFileTags: Record<Locale, string> = {
  ja: "ja",
  zh: "zh-CN",
  en: "en",
  ko: "ko",
};

type PageProps = {
  params: Promise<{ locale: string; document: string }>;
};

function isDocument(value: string): value is LegalDocument {
  return documents.includes(value as LegalDocument);
}

export function generateStaticParams() {
  return locales.flatMap((locale) => documents.map((document) => ({ locale, document })));
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale, document } = await params;
  if (!isLocale(locale) || !isDocument(document)) return {};
  return { title: `${copy[locale].legal[document]} — Furigana Keyboard` };
}

async function legalText(locale: Locale, document: LegalDocument): Promise<string> {
  const prefix = document === "terms" ? "terms" : "privacy-policy";
  return readFile(
    path.join(process.cwd(), "content", "legal", `${prefix}-${localeFileTags[locale]}.txt`),
    "utf8",
  );
}

export default async function LegalPage({ params }: PageProps) {
  const { locale, document } = await params;
  if (!isLocale(locale) || !isDocument(document)) notFound();
  const lines = (await legalText(locale, document)).split(/\r?\n/).filter(Boolean);

  return (
    <>
      <SiteHeader locale={locale} />
      <main className="legal-page shell">
        <a className="legal-back" href={`/${locale}`}>← Furigana Keyboard</a>
        <article className="legal-copy">
          <h1>{lines[0]}</h1>
          <p className="legal-effective">{lines[1]}</p>
          {lines.slice(2).map((line, index) => {
            if (/^\d+\./.test(line)) return <h2 key={index}>{line}</h2>;
            if (line === "support@hanlu.app") {
              return <p key={index}><a className="legal-contact" href="mailto:support@hanlu.app">support@hanlu.app</a></p>;
            }
            return <p key={index}>{line}</p>;
          })}
        </article>
      </main>
      <SiteFooter locale={locale} />
    </>
  );
}
