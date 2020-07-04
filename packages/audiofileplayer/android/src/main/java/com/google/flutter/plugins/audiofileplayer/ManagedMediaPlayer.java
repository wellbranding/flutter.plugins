package com.google.flutter.plugins.audiofileplayer;

import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.ref.WeakReference;

/** Base class for wrapping a MediaPlayer for use by AudiofileplayerPlugin. */
abstract class ManagedMediaPlayer
    implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnSeekCompleteListener {
  private static final String TAG = ManagedMediaPlayer.class.getSimpleName();
  public static final int PLAY_TO_END = -1;
  protected FileDescriptor fileDescription;
  protected long startOffset;
  protected long getLengh;

  interface OnSeekCompleteListener {
    /** Called when asynchronous seeking has completed. */
    void onSeekComplete();
  }

  protected final AudiofileplayerPlugin parentAudioPlugin;
  protected final String audioId;
  protected final boolean playInBackground;
  protected MediaPlayer player;
  protected MediaPlayer nextPlayer;
  AssetFileDescriptor assetFileDescriptor;
  final Handler handler;
  final Runnable pauseAtEndpointRunnable;
  double volume = 0.5;
  private OnSeekCompleteListener onSeekCompleteListener;
  String path;

  /** Runnable which repeatedly sends the player's position. */
  private final Runnable updatePositionData =
      new Runnable() {
        @Override
        public void run() {
          try {
            if(player==null){
              return;
            }
            if (player.isPlaying()) {
              double positionSeconds = (double) player.getCurrentPosition() / 1000.0;
              parentAudioPlugin.handlePosition(audioId, positionSeconds);
            }
            handler.postDelayed(this, 250);
          } catch (Exception e) {
            Log.e(TAG, "Could not schedule position update for player", e);
          }
        }
      };

  protected ManagedMediaPlayer(
      String audioId,
      AudiofileplayerPlugin parentAudioPlugin,
      boolean looping,
      boolean playInBackground) {
    this.parentAudioPlugin = parentAudioPlugin;
    this.audioId = audioId;
    this.playInBackground = playInBackground;
    player = new MediaPlayer();
    //not setting looping, because it is custom loop
    //player.setLooping(looping);

    pauseAtEndpointRunnable = new PauseAtEndpointRunnable(this);

    handler = new Handler();
    handler.post(updatePositionData);
  }


  public void setOnSeekCompleteListener(OnSeekCompleteListener onSeekCompleteListener) {
    this.onSeekCompleteListener = onSeekCompleteListener;
  }


  public String getAudioId() {
    return audioId;
  }

  public double getDurationSeconds() {
    return (double) player.getDuration() / 1000.0; // Convert ms to seconds.
  }

  /**
   * Plays the audio.
   *
   * @param endpointMs the time, in milleseconds, to play to. To play until the end, pass {@link
   *     #PLAY_TO_END}.
   */
  public void play(boolean playFromStart, int endpointMs) {
    if (playFromStart) {
      player.seekTo(0);
    }
    if (endpointMs == PLAY_TO_END) {
      handler.removeCallbacks(pauseAtEndpointRunnable);
      player.start();
    } else {
      // If there is an endpoint, check that it is in the future, then start playback and schedule
      // the pausing after a duration.
      int positionMs = player.getCurrentPosition();
      int durationMs = endpointMs - positionMs;
      Log.i(TAG, "Called play() at " + positionMs + " ms, to play for " + durationMs + " ms.");
      if (durationMs <= 0) {
        Log.w(TAG, "Called play() at position after endpoint. No playback occurred.");
        return;
      }
      handler.removeCallbacks(pauseAtEndpointRunnable);
      player.start();
      handler.postDelayed(pauseAtEndpointRunnable, durationMs);
    }
  }

  /** Releases the underlying MediaPlayer. */
  public void release() {
    if(nextPlayer!=null){
      nextPlayer.stop();
    }
    player.stop();
    player.reset();
    player.release();
    player.setOnErrorListener(null);
    player.setOnCompletionListener(null);
    player.setOnPreparedListener(null);
    player.setOnSeekCompleteListener(null);
    handler.removeCallbacksAndMessages(null);
  }

  public void seek(double positionSeconds) {
    int positionMilliseconds = (int) (positionSeconds * 1000.0);
    player.seekTo(positionMilliseconds);
  }

  public void setVolume(double volume) {
    this.volume = (float) volume;
    player.setVolume((float) volume, (float) volume);
    if(nextPlayer!=null){
      nextPlayer.setVolume((float) volume, (float) volume);
    }
  }

  public void pause()
  {
    player.pause();
  }



  public void createNextMediaPlayer(FileDescriptor file, long startOffset, long lenght) {
    Log.d(TAG, "trying to create second" + volume);
    nextPlayer = new MediaPlayer();
    try {
      nextPlayer.setDataSource(file, startOffset, lenght);
      nextPlayer.setVolume((float) volume, (float)volume);
      nextPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(MediaPlayer mp) {
          nextPlayer.seekTo(0);
          player.setNextMediaPlayer(nextPlayer);
          player.setOnErrorListener(ManagedMediaPlayer.this);
          player.setOnCompletionListener(ManagedMediaPlayer.this);
          player.setOnSeekCompleteListener(ManagedMediaPlayer.this);
        }
      });
      nextPlayer.prepareAsync();
    } catch (IOException e) {
      Log.d(TAG, "failed to create second mediaplayer" + e.toString());
      e.printStackTrace();
    }
  }




  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public void onCompletion(MediaPlayer mediaPlayer) {
    player.seekTo(0);
    player = nextPlayer;
    createNextMediaPlayer(this.fileDescription, this.startOffset, this.getLengh);
    mediaPlayer.release();
    parentAudioPlugin.handleCompletion(this.audioId);
  }

  /**
   * Callback to indicate an error condition.
   *
   * <p>NOTE: {@link #onError(MediaPlayer, int, int)} must be properly implemented and return {@code
   * true} otherwise errors will repeatedly call {@link #onCompletion(MediaPlayer)}.
   */
  @Override
  public boolean onError(MediaPlayer mp, int what, int extra) {
    Log.e(TAG, "onError: what:" + what + " extra: " + extra);
    return true;
  }

  @Override
  public void onSeekComplete(MediaPlayer mp) {
    if (onSeekCompleteListener != null) {
      onSeekCompleteListener.onSeekComplete();
    }
  }

  /** Pauses the player and notifies of completion. */
  private static class PauseAtEndpointRunnable implements Runnable {

    final WeakReference<ManagedMediaPlayer> managedMediaPlayerRef;

    PauseAtEndpointRunnable(ManagedMediaPlayer managedMediaPlayer) {
      managedMediaPlayerRef = new WeakReference<>(managedMediaPlayer);
    }

    @Override
    public void run() {
      Log.d(TAG, "Running scheduled PauseAtEndpointRunnable");

      ManagedMediaPlayer managedMediaPlayer = managedMediaPlayerRef.get();
      if (managedMediaPlayer == null) {
        Log.w(TAG, "ManagedMediaPlayer no longer active.");
        return;
      }
      managedMediaPlayer.player.pause();
      managedMediaPlayer.parentAudioPlugin.handleCompletion(managedMediaPlayer.audioId);
    }
  }
}
