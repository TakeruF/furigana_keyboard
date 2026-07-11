import type { Metadata } from "next";
import "../globals.css";

export const metadata: Metadata = {
  title: "Furigana Keyboard",
  description: "Offline Japanese handwriting keyboard with furigana candidates.",
  icons: {
    icon: [{ url: "/app-icon.png", type: "image/png" }],
    apple: "/app-icon.png",
  },
};

export default function RootRedirectLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  );
}
