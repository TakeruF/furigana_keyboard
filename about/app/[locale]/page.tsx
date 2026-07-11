import { notFound } from "next/navigation";
import Image from "next/image";
import { copy, isLocale, locales } from "../i18n";
import SiteFooter from "../components/SiteFooter";
import SiteHeader from "../components/SiteHeader";
import { AndroidIcon, AppleIcon } from "../components/PlatformIcons";
import { releaseLinks } from "../release-links";

type PageProps = { params: Promise<{ locale: string }> };

export function generateStaticParams() {
  return locales.map((locale) => ({ locale }));
}

function ReleaseAction({ href, availableLabel, pending, pendingLabel }: { href: string; availableLabel: string; pending: string; pendingLabel: string }) {
  if (!href) {
    return <span className="release-action is-pending" aria-label={pendingLabel}>{pending}</span>;
  }
  return <a className="release-action" href={href} rel="noreferrer">{availableLabel}<span aria-hidden="true">↗</span></a>;
}

export default async function LocalePage({ params }: PageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const content = copy[locale];

  return (
    <>
      <SiteHeader locale={locale} />
      <main>
      <section className="hero" id="top">
        <div className="hero-copy">
          <Image className="hero-app-icon" src="/app-icon.png" alt="" width={96} height={96} priority />
          <h1>{content.hero.title[0]}<br /><em>{content.hero.title[1]}</em></h1>
          <p className="hero-lead">{content.hero.lead[0]}<br />{content.hero.lead[1]}</p>
          <div className="hero-downloads" aria-label={content.nav.download}>
            <a className="hero-download primary" href={releaseLinks.androidApk}>
              <AndroidIcon /> {content.download.apk}
            </a>
            {releaseLinks.appStore ? (
              <a className="hero-download secondary" href={releaseLinks.appStore}>
                <AppleIcon /> {content.download.appStore}
              </a>
            ) : (
              <span className="hero-download secondary is-disabled">
                <AppleIcon /> {content.download.appStore} · {content.download.pending}
              </span>
            )}
          </div>
        </div>

        <figure className="keyboard-stage">
          <Image
            className="keyboard-preview"
            src="/keyboard-preview.jpg"
            alt={content.hero.keyboardImage}
            width={1080}
            height={2354}
            priority
            sizes="(max-width: 820px) min(88vw, 390px), 390px"
          />
        </figure>
      </section>

      <section className="feature-section" id="features">
        <div className="shell">
          <div className="section-heading"><p>WHY FURIGANA KEYBOARD</p><h2>{content.why.heading}</h2></div>
          <div className="feature-grid">
            {content.why.features.map((feature, index) => (
              <article key={feature.title}><span>0{index + 1}</span><h3>{feature.title}</h3><p>{feature.body}</p></article>
            ))}
          </div>
        </div>
      </section>

      <section className="download-section shell" id="download">
        <div className="section-heading compact"><p>DOWNLOAD</p><h2>{content.download.heading}</h2><span>{content.download.intro}</span></div>
        <div className="release-grid">
          <article className="release-card android">
            <div className="platform-icon" aria-hidden="true"><AndroidIcon className="platform-glyph" /></div>
            <div><p className="platform">ANDROID</p><h3>{content.download.androidTitle}</h3><p>{content.download.androidBody}</p></div>
            <ReleaseAction href={releaseLinks.androidApk} availableLabel={content.download.apk} pending={content.download.pending} pendingLabel={content.download.pendingLabel} />
          </article>
          <article className="release-card ios">
            <div className="platform-icon" aria-hidden="true"><AppleIcon className="platform-glyph" /></div>
            <div><p className="platform">IPHONE &amp; IPAD</p><h3>{content.download.iosTitle}</h3><p>{content.download.iosBody}</p></div>
            <ReleaseAction href={releaseLinks.appStore} availableLabel={content.download.appStore} pending={content.download.pending} pendingLabel={content.download.pendingLabel} />
          </article>
        </div>
      </section>

      <section className="about-section" id="about">
        <div className="shell about-grid">
          <div className="about-title"><p>ABOUT</p><h2>{content.about.heading[0]}<br />{content.about.heading[1]}</h2></div>
          <div className="about-copy">
            <p className="large-copy">{content.about.lead}</p><p>{content.about.body}</p>
            <div className="facts">
              <div><strong>6,356</strong><span>{content.about.facts[0]}</span></div>
              <div><strong>13,108</strong><span>{content.about.facts[1]}</span></div>
              <div><strong>0</strong><span>{content.about.facts[2]}</span></div>
            </div>
          </div>
        </div>
      </section>

      </main>
      <SiteFooter locale={locale} />
    </>
  );
}
