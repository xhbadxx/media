package androidx.media3.exoplayer.dash;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import java.util.Locale;

public class LLConfiguration {

  private String minLiveOffset = "";
  private String targetLiveOffset = "";
  private String maxLiveOffset = "";
  private String minPlaybackSpeed = "";
  private String maxPlaybackSpeed = "";

  public void updateMinLiveOffset(long value, boolean fromServer) {
    if (value == C.TIME_UNSET) {
      this.minLiveOffset = "#";
      return;
    }
    if (fromServer) {
      this.minLiveOffset = String.format(Locale.getDefault(), "%ds", value/1000);
    } else {
      this.minLiveOffset = String.format(Locale.getDefault(), "(%ds)", value/1000);
    }
  }

  public void updateTargetLiveOffset(long value, boolean fromServer) {
    if (value == C.TIME_UNSET) {
      this.targetLiveOffset = "#";
      return;
    }
    if (fromServer) {
      this.targetLiveOffset = String.format(Locale.getDefault(), "%ds", value/1000);
    } else {
      this.targetLiveOffset = String.format(Locale.getDefault(), "(%ds)", value/1000);
    }
  }

  public void updateMaxLiveOffset(long value, boolean fromServer) {
    if (value == C.TIME_UNSET) {
      this.maxLiveOffset = "#";
      return;
    }
    if (fromServer) {
      this.maxLiveOffset = String.format(Locale.getDefault(), "%ds", value/1000);
    } else {
      this.maxLiveOffset = String.format(Locale.getDefault(), "(%ds)", value/1000);
    }
  }

  public void updateMinPlaybackSpeed(float value, boolean fromServer) {
    if (value == C.RATE_UNSET) {
      this.minPlaybackSpeed = "#";
      return;
    }
    if (fromServer) {
      this.minPlaybackSpeed = String.format(Locale.getDefault(), "%.2f", value);
    } else {
      this.minPlaybackSpeed = String.format(Locale.getDefault(), "(%.2f)", value);
    }
  }

  public void updateMaxPlaybackSpeed(float value, boolean fromServer) {
    if (value == C.RATE_UNSET) {
      this.maxPlaybackSpeed = "#";
      return;
    }
    if (fromServer) {
      this.maxPlaybackSpeed = String.format(Locale.getDefault(), "%.2f", value);
    } else {
      this.maxPlaybackSpeed = String.format(Locale.getDefault(), "(%.2f)", value);
    }
  }

  public void reset()  {
    this.minLiveOffset = "";
    this.targetLiveOffset = "";
    this.maxLiveOffset = "";
    this.minPlaybackSpeed = "";
    this.maxPlaybackSpeed = "";
  }

  @NonNull
  @Override
  public String toString() {
    if (minLiveOffset.isEmpty() && targetLiveOffset.isEmpty() && maxLiveOffset.isEmpty() && minPlaybackSpeed.isEmpty() && maxPlaybackSpeed.isEmpty())
      return "";
    else
      return "[" + minLiveOffset + ", " + targetLiveOffset + ", " + maxLiveOffset + ", " + minPlaybackSpeed + ", " + maxPlaybackSpeed + "]";
  }

}
