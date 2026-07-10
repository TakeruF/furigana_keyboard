import { notFound } from "next/navigation";
import { copy, isLocale, localeLabels, locales } from "../i18n";

const releaseLinks = {
  androidApk: process.env.NEXT_PUBLIC_ANDROID_APK_URL ?? "",
  appStore: process.env.NEXT_PUBLIC_APP_STORE_URL ?? "",
};

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
    <main>
      <nav className="nav shell" aria-label={content.nav.label}>
        <a className="brand" href={`/${locale}#top`} aria-label={content.nav.top}>
          <span className="brand-mark" aria-hidden="true">振</span>
          <span>Furigana Keyboard</span>
        </a>
        <div className="nav-actions">
          <div className="nav-links">
            <a href="#features">{content.nav.features}</a>
            <a href="#download">{content.nav.download}</a>
            <a href="#about">{content.nav.about}</a>
          </div>
          <div className="language-switcher" aria-label={content.nav.language}>
            {locales.map((item) => (
              <a key={item} href={`/${item}`} hrefLang={copy[item].htmlLang} lang={copy[item].htmlLang} aria-current={item === locale ? "page" : undefined} aria-label={localeLabels[item].full} title={localeLabels[item].full}>
                {localeLabels[item].short}
              </a>
            ))}
          </div>
        </div>
      </nav>

      <section className="hero shell" id="top">
        <div className="hero-copy">
          <p className="eyebrow"><span /> Private by design · Offline first</p>
          <h1>{content.hero.title[0]}<br /><em>{content.hero.title[1]}</em></h1>
          <p className="hero-lead">{content.hero.lead[0]}<br />{content.hero.lead[1]}</p>
          <a className="text-link" href="#download">{content.hero.release} <span aria-hidden="true">↓</span></a>
        </div>

        <div className="keyboard-stage" aria-label={content.hero.keyboardImage}>
          <div className="candidate-row">
            <span><b>振</b><small>ふる</small></span>
            <span><b>震</b><small>しん</small></span>
            <span><b>辰</b><small>たつ</small></span>
          </div>
          <div className="writing-area">
            <i className="stroke stroke-one" /><i className="stroke stroke-two" /><i className="stroke stroke-three" /><i className="stroke stroke-four" />
            <p>{content.hero.writingHint}</p>
          </div>
          <div className="key-row" aria-hidden="true">
            <span>{content.hero.keys[0]}</span><span>{content.hero.keys[1]}</span><span>{content.hero.keys[2]}</span><span className="accent-key">{content.hero.keys[3]}</span>
          </div>
          <div className="privacy-pill"><span aria-hidden="true">●</span> {content.hero.privateStatus}</div>
        </div>
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
            <div className="platform-icon" aria-hidden="true">A</div>
            <div><p className="platform">ANDROID</p><h3>{content.download.androidTitle}</h3><p>{content.download.androidBody}</p></div>
            <ReleaseAction href={releaseLinks.androidApk} availableLabel={content.download.apk} pending={content.download.pending} pendingLabel={content.download.pendingLabel} />
          </article>
          <article className="release-card ios">
            <div className="platform-icon" aria-hidden="true">●</div>
            <div><p className="platform">IPHONE &amp; IPAD</p><h3>{content.download.iosTitle}</h3><p>{content.download.iosBody}</p></div>
            <ReleaseAction href={releaseLinks.appStore} availableLabel={content.download.appStore} pending={content.download.pending} pendingLabel={content.download.pendingLabel} />
          </article>
        </div>
        <p className="release-note">{content.download.note}</p>
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

      <footer className="shell">
        <div className="brand footer-brand"><span className="brand-mark" aria-hidden="true">振</span><span>Furigana Keyboard</span></div>
        <p>{content.footer}</p>
        <a className="support-link" href="mailto:support@hanlu.app">{content.support}: support@hanlu.app</a>
        <p>© 2026 Furigana Keyboard</p>
      </footer>
    </main>
  );
}
