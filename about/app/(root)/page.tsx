import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { preferredLocale } from "../../lib/preferred-locale";

export default async function RootPage() {
  const requestHeaders = await headers();
  redirect(`/${preferredLocale(requestHeaders.get("accept-language"))}`);
}
