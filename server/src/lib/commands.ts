/**
 * Local voice commands — handled by code, without calling the AI model (no LLM cost, instant).
 * Radio streams are played by the tablet itself.
 */

export interface RadioStation {
  name: string;
  aliases: string[];
  url: string;
}

export const DEFAULT_STATIONS: RadioStation[] = [
  { name: 'גלגלצ', aliases: ['גלגלצ', 'גלגל״צ', 'גלגל"צ', 'גלגלץ', 'גלגל צ'], url: 'https://glzwizzlv.bynetcdn.com/glglz_mp3' },
  { name: 'גלי צה"ל', aliases: ['גלי צהל', 'גלי צה"ל', 'גלי צה״ל', 'גלצ', 'גל"צ', 'גל״צ'], url: 'https://glzwizzlv.bynetcdn.com/glz_mp3' },
  { name: '103FM', aliases: ['103', '103fm', 'מאה ושלוש', 'רדיו 103'], url: 'https://cdn.cybercdn.live/103FM/Live/icecast.audio' },
  { name: 'eco99fm', aliases: ['99', 'eco99', 'אקו 99', 'אקו', 'תשעים ותשע', '99fm'], url: 'https://99.mediacast.co.il/99fm_aac' },
  { name: 'כאן 88', aliases: ['כאן 88', 'כאן שמונים ושמונה', '88'], url: 'https://kanliveicy.media.kan.org.il/icy/kan88_mp3' },
  { name: 'כאן ב', aliases: ['כאן ב', 'כאן בית', 'רשת ב'], url: 'https://kanliveicy.media.kan.org.il/icy/kanbet_mp3' },
];

export type LocalCommand =
  | { kind: 'sleep'; reply: string }
  | { kind: 'radio-play'; station: RadioStation; reply: string }
  | { kind: 'radio-stop'; reply: string }
  | { kind: 'volume'; delta: number; reply: string };

const clean = (s: string) =>
  s
    .replace(/(^|\s)([להבמו])[-־]/g, '$1$2')
    .replace(/[.,!?״"'׳]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase();

const PLAY = /(^|\s)(תפעיל|תפעילי|הפעל|תדליק|תדליקי|הדלק|שים|תשים|תשימי|נגן|תנגן|תנגני|תעביר|תחליף|העבר)(\s|$)/;
const STOP = /(^|\s)(תכבה|תכבי|כבה|תעצור|תעצרי|עצור|תפסיק|תפסיקי|הפסק|תשתיק|השתק|די)(\s|$)/;
const RADIO_WORD = /(^|\s)(ה?רדיו|ה?מוזיקה|ה?שיר|ה?תחנה)(\s|$)/;

export function matchStation(text: string, stations: RadioStation[]): RadioStation | null {
  const t = ' ' + clean(text) + ' ';
  let best: { s: RadioStation; len: number } | null = null;
  for (const s of stations) {
    for (const a of [s.name, ...s.aliases]) {
      const alias = clean(a);
      if (!alias) continue;
      // Whole-word match (so "99" doesn't match "1999"); allow Hebrew prefix letters (ה/ל/ב/את ה).
      const re = new RegExp(`(^|\\s)(ה|ל|ב|את ה|את )?${alias.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(\\s|$)`);
      if (re.test(t) && (!best || alias.length > best.len)) best = { s, len: alias.length };
    }
  }
  return best?.s ?? null;
}

export function parseLocalCommand(text: string, stations: RadioStation[] = DEFAULT_STATIONS): LocalCommand | null {
  const t = clean(text).replace(/^(היי |הי )?(ג ?ארביס|גארביס|jarvis)\s*/, '');

  if (/(^|\s)(לך|תלך|ללכת|לכי|תלכי|הולך)\s*(ל)?(ישון|לישון|שון)(\s|$)|מצב שינה|לילה טוב|תפסיק להקשיב|(^|\s)(זהו )?סיימנו(\s|$)|go to sleep/.test(t)) {
    return { kind: 'sleep', reply: 'בסדר אבי, הולך לישון. תקרא לי כשתצטרך.' };
  }

  // Volume: "תנמיך", "יותר חזק", "תעלה את הווליום", "תגביר הרבה" — one step each time.
  const VOL_WORD = /(ה?ווליום|ה?וליום|ה?עוצמה|ה?קול|ה?רדיו|ה?מוזיקה)/;
  const much = /(הרבה|מאוד|ממש)/.test(t) ? 2 : 1;
  if (/(^|\s)(תנמיך|תנמיכי|הנמך|להנמיך)(\s|$)|יותר (חלש|בשקט|נמוך)|(חלש|שקט|נמוך) יותר/.test(t) || (/(^|\s)(תוריד|תורידי|הורד)(\s|$)/.test(t) && VOL_WORD.test(t))) {
    return { kind: 'volume', delta: -2 * much, reply: '' };
  }
  if (/(^|\s)(תגביר|תגבירי|הגבר|להגביר|תגביה|תגביהי)(\s|$)|יותר (חזק|גבוה)|(חזק|גבוה) יותר/.test(t) || (/(^|\s)(תעלה|תעלי|העלה|תרים|תרימי)(\s|$)/.test(t) && VOL_WORD.test(t))) {
    return { kind: 'volume', delta: 2 * much, reply: '' };
  }

  if (STOP.test(t) && (RADIO_WORD.test(t) || matchStation(t, stations))) {
    return { kind: 'radio-stop', reply: 'כיביתי את הרדיו.' };
  }

  if (PLAY.test(t) || RADIO_WORD.test(t)) {
    const station = matchStation(t, stations);
    if (station) return { kind: 'radio-play', station, reply: `מפעיל את ${station.name}.` };
    if (PLAY.test(t) && /(^|\s)ה?רדיו(\s|$)/.test(t)) {
      const s = stations[0]!;
      return { kind: 'radio-play', station: s, reply: `מפעיל את ${s.name}.` };
    }
  }
  return null;
}

/** Back-compat helper used by tests. */
export function isSleepCommand(text: string) {
  return parseLocalCommand(text)?.kind === 'sleep';
}
