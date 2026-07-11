const supportedLocales = new Set(["ja", "en", "ko"]);

/** Select the best supported UI language from an HTTP Accept-Language value. */
export function preferredLocale(acceptLanguage: string | null): string {
  const preferences = (acceptLanguage ?? "")
    .split(",")
    .map((entry, index) => {
      const [tag, ...parameters] = entry.trim().toLowerCase().split(";");
      const qualityParameter = parameters.find((parameter) => parameter.trim().startsWith("q="));
      const quality = qualityParameter ? Number(qualityParameter.trim().slice(2)) : 1;
      let language = tag.split("-")[0];
      if (language === "zh") {
        const subtags = tag.split("-");
        const traditional = subtags.some((subtag) => ["hant", "tw", "hk", "mo"].includes(subtag));
        language = traditional ? "zh-Hant" : "zh-Hans";
      }
      return { language, quality: Number.isFinite(quality) ? quality : 0, index };
    })
    .filter(({ quality }) => quality > 0)
    .sort((left, right) => right.quality - left.quality || left.index - right.index);

  return preferences.find(({ language }) => language.startsWith("zh-") || supportedLocales.has(language))?.language ?? "ja";
}
