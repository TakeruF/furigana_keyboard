"use client";

import Image from "next/image";
import { ChevronDown } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { copy, type Locale } from "../i18n";
import { releaseLinks } from "../release-links";
import { AndroidIcon, AppleIcon } from "./PlatformIcons";

export default function SiteHeader({ locale }: { locale: Locale }) {
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const content = copy[locale];

  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent | TouchEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("touchstart", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("touchstart", close);
    };
  }, [open]);

  return (
    <header className="site-header">
      <nav className="site-nav" aria-label={content.nav.label}>
        <a className="site-brand" href={`/${locale}`} aria-label={content.nav.top}>
          <Image src="/app-icon.png" alt="" width={24} height={24} />
          <span>Furigana Keyboard Beta</span>
        </a>
        <div className="header-actions">
          <div ref={menuRef} className="relative">
            <button
              type="button"
              onClick={() => setOpen((value) => !value)}
              aria-expanded={open}
              className="inline-flex items-center gap-1 rounded-full bg-zinc-900 px-4 py-1.5 text-xs font-semibold text-white transition hover:bg-zinc-800 active:scale-[0.97]"
            >
              {content.nav.download}
              <ChevronDown size={13} strokeWidth={2} className={`transition-transform ${open ? "rotate-180" : ""}`} />
            </button>
            {open && (
              <div className="absolute right-0 mt-2 min-w-full overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-xl">
                <a
                  href={releaseLinks.androidApk}
                  onClick={() => setOpen(false)}
                  className="flex items-center gap-2.5 whitespace-nowrap px-4 py-2.5 text-xs text-zinc-700 transition hover:bg-zinc-50"
                >
                  <AndroidIcon /> Android
                </a>
                {releaseLinks.appStore ? (
                  <a
                    href={releaseLinks.appStore}
                    onClick={() => setOpen(false)}
                    className="flex items-center gap-2.5 whitespace-nowrap border-t border-zinc-100 px-4 py-2.5 text-xs text-zinc-700 transition hover:bg-zinc-50"
                  >
                    <AppleIcon /> iOS
                  </a>
                ) : (
                  <span className="flex items-center gap-2.5 whitespace-nowrap border-t border-zinc-100 px-4 py-2.5 text-xs text-zinc-400">
                    <AppleIcon /> iOS · {content.download.pending}
                  </span>
                )}
              </div>
            )}
          </div>
        </div>
      </nav>
    </header>
  );
}
