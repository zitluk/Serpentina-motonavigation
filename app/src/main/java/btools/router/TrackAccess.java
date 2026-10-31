package btools.router;

import java.util.ArrayList;
import java.util.List;

/**
 * Přístup k package-private datům BRouteru (pokyny pro navigaci, tagy cest).
 * Leží záměrně v balíčku btools.router.
 */
public final class TrackAccess {

  public static final class Hint {
    /** Index bodu v OsmTrack.nodes, kde se manévr provádí. */
    public int index;
    /** Kód manévru: C, TL, TSLL, TSHL, TR, TSLR, TSHR, KL, KR, TU, RNDB, RNLB, EL, ER, END. */
    public String command;
    /** Číslo výjezdu z kruhového objezdu (0 = není kruháč). */
    public int exit;
    /** Tagy cesty, na kterou se odbočuje (highway=…). */
    public String wayTags;
  }

  private TrackAccess() {
  }

  public static List<Hint> hints(OsmTrack track) {
    List<Hint> result = new ArrayList<>();
    if (track.voiceHints == null) return result;
    for (VoiceHint vh : track.voiceHints.list) {
      Hint h = new Hint();
      h.index = vh.indexInTrack;
      h.command = command(vh.cmd);
      h.exit = Math.abs(vh.roundaboutExit);
      h.wayTags = vh.goodWay == null ? null : vh.goodWay.wayKeyValues;
      result.add(h);
    }
    return result;
  }

  /** Tagy cesty vedoucí do daného bodu trasy (highway=…, maxspeed=…). */
  public static String wayTags(OsmPathElement e) {
    return e.message == null ? null : e.message.wayKeyValues;
  }

  private static String command(int cmd) {
    switch (cmd) {
      case VoiceHint.TL: return "TL";
      case VoiceHint.TSLL: return "TSLL";
      case VoiceHint.TSHL: return "TSHL";
      case VoiceHint.TR: return "TR";
      case VoiceHint.TSLR: return "TSLR";
      case VoiceHint.TSHR: return "TSHR";
      case VoiceHint.KL: return "KL";
      case VoiceHint.KR: return "KR";
      case VoiceHint.TLU:
      case VoiceHint.TRU:
      case VoiceHint.TU: return "TU";
      case VoiceHint.RNDB: return "RNDB";
      case VoiceHint.RNLB: return "RNLB";
      case VoiceHint.EL: return "EL";
      case VoiceHint.ER: return "ER";
      case VoiceHint.END: return "END";
      default: return "C";
    }
  }
}
