package com.philliphsu.clock2.ringtone;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.philliphsu.clock2.Alarm;
import com.philliphsu.clock2.R;

import static com.philliphsu.clock2.util.DateFormatUtils.formatTime;
import static com.philliphsu.clock2.util.Preconditions.checkNotNull;

/**
 * Runs in the foreground. While it can still be killed by the system, it stays alive significantly
 * longer than if it does not run in the foreground. The longevity should be sufficient for practical
 * use. In fact, if the app is used properly, longevity should be a non-issue; realistically, the lifetime
 * of the RingtoneService will be tied to that of its RingtoneActivity because users are not likely to
 * navigate away from the Activity without making an action. But if they do accidentally navigate away,
 * they have plenty of time to make the desired action via the notification.
 *
 * This is both a started and bound service. See https://developer.android.com/guide/components/bound-services.html#Lifecycle
 * "... the service runs until the service stops itself with stopSelf() or another component
 * calls stopService(), regardless of whether it is bound to any clients."
 * The regardless phrase didn't work for me. I had to unbind in RingtoneActivity first before calling
 * stopSelf() on this service for the ringtone to stop playing.
 * TODO: Consider making this purely a bound service, so you don't have to bind/unbind AND start/stop
 * manually. Instead of implementing onStartCommand() and calling startService(), you would write a public
 * method that the activity calls to start playing the ringtone. When the activity calls its onDestroy(), it unbinds
 * itself from this service, and the system will know to destroy this service instead of you manually
 * calling stopSelf() or stopService().
 */
public class RingtoneService extends Service { // TODO: abstract this, make subclasses
    private static final String TAG = "RingtoneService";

    private AudioManager mAudioManager;
    private Ringtone mRingtone;
    private Alarm mAlarm;
    private String mNormalRingTime;
    private boolean mAutoSilenced = false;
    private RingtoneCallback mRingtoneCallback;
    // TODO: Using Handler for this is ill-suited? Alarm ringing could outlast the
    // application's life. Use AlarmManager API instead.
    private final Handler mSilenceHandler = new Handler();
    private final Runnable mSilenceRunnable = new Runnable() {
        @Override
        public void run() {
            mAutoSilenced = true;
            if (mRingtoneCallback != null) {
                // Finish the activity, which fires onDestroy() and then unbinds itself from this service.
                // All clients must be unbound before stopSelf() (and stopService()?) will succeed.
                // See https://developer.android.com/guide/components/bound-services.html#Lifecycle
                // Figure 1 regarding the lifecycle of started and bound services.
                mRingtoneCallback.onAutoSilence();
            }
        }
    };
    private final IBinder mBinder = new RingtoneBinder();

    // TODO: Apply the setting for "Silence after" here by using an AlarmManager to
    // schedule an alarm in the future to stop this service, and also update the foreground
    // notification to say "alarm missed" in the case of Alarms or "timer expired" for Timers.
    // If Alarms and Timers will have distinct settings for this, then consider doing this
    // operation in the respective subclass of this service.

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");
        mRingtone.stop();
        mAudioManager.abandonAudioFocus(null); // no listener was set
        mSilenceHandler.removeCallbacks(mSilenceRunnable);
        if (mAutoSilenced) {
            // Post notification that alarm was missed, or timer expired.
            // TODO: You should probably do this in the appropriate subclass.
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification note = new NotificationCompat.Builder(this)
                    .setContentTitle(getString(R.string.missed_alarm))
                    .setContentText(mNormalRingTime)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();
            // A tag with the name of the subclass is used in addition to the item's id to prevent
            // conflicting notifications for items of different class types. Items of any class type
            // have ids starting from 0.
            nm.notify(getClass().getName(), mAlarm.intId(), note);
        }
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void playRingtone(@NonNull Alarm alarm) {
        if (mAudioManager == null && mRingtone == null) {
            mAlarm = checkNotNull(alarm);
            // TODO: The below call requires a notification, and there is no way to provide one suitable
            // for both Alarms and Timers. Consider making this class abstract, and have subclasses
            // implement an abstract method that calls startForeground(). You would then call that
            // method here instead.
            String title = mAlarm.label().isEmpty()
                    ? getString(R.string.alarm)
                    : mAlarm.label();
            mNormalRingTime = formatTime(this, System.currentTimeMillis()); // now
            Notification note = new NotificationCompat.Builder(this)
                    // Required contents
                    .setSmallIcon(R.mipmap.ic_launcher) // TODO: alarm icon
                    .setContentTitle(title)
                    .setContentText(mNormalRingTime)
                    .build();
            startForeground(R.id.ringtone_service_notification, note); // TOneverDO: Pass 0 as the first argument

            mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            // Request audio focus first, so we don't play our ringtone on top of any
            // other apps that currently have playback.
            int result = mAudioManager.requestAudioFocus(
                    null, // Playback will likely be short, so don't worry about listening for focus changes
                    AudioManager.STREAM_ALARM,
                    // Request permanent focus, as ringing could last several minutes
                    AudioManager.AUDIOFOCUS_GAIN);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Uri ringtone = Uri.parse(mAlarm.ringtone());
                mRingtone = RingtoneManager.getRingtone(this, ringtone);
                // Deprecated, but the alternative AudioAttributes requires API 21
                mRingtone.setStreamType(AudioManager.STREAM_ALARM);
                mRingtone.play();
                scheduleAutoSilence();
            }
        }
    }

    public void setRingtoneCallback(RingtoneCallback callback) {
        mRingtoneCallback = callback;
    }

    /**
     * A way for clients to interrupt the currently running instance of this service. Interrupting
     * the service is akin to prematurely auto silencing the ringtone right now. <b>Clients MUST
     * unbind from this service immediately after interrupting.</b>
     */
    public void interrupt() {
        mAutoSilenced = true;
    }

    // Needed so clients can get the Service instance and e.g. call setRingtoneCallback().
    public class RingtoneBinder extends Binder {
        RingtoneService getService() {
            return RingtoneService.this;
        }
    }

    public interface RingtoneCallback {
        void onAutoSilence();
    }

    private void scheduleAutoSilence() {
        // TODO: Read prefs
        //SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        /*int minutes = Integer.parseInt(pref.getString(
                getString(R.string.key_silence_after),
                "15"));*/
        //mSilenceHandler.postDelayed(mSilenceRunnable, 10000);
    }
}
