package androidx.media3;

import androidx.media3.common.Player;

public interface PreviousNextDispatcher {
  public void dispatcherNext(Player player);
  public void dispatcherPrevious(Player player);
}