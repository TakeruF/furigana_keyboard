const supportedLocales = new Set(["ja", "zh", "en", "ko"]);

/** Select the best supported UI language from an HTTP Accept-Language value. */
export function preferredLocale(acceptLanguage: string | null): string {
  const preferences = (acceptLanguage ?? "")
    .split(",")
    .map((entry, index) => {
      const [tag, ...parameters] = entry.trim().toLowerCase().split(";");
      const qualityParameter = parameters.find((parameter) => parameter.trim().startsWith("q="));
      const quality = qualityParameter ? Number(qualityParameter.trim().slice(2)) : 1;
      return { language: tag.split("-")[0], quality: Number.isFinite(quality) ? quality : 0, index };
    })
    .filter(({ quality }) => quality > 0)
    .sort((left, right) => right.quality - left.quality || left.index - right.index);

  return preferences.find(({ language }) => supportedLocales.has(language))?.language ?? "ja";
}
