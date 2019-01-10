/*
 * ============================================================================
 * Copyright (C) 2017 Kaltura Inc.
 *
 * Licensed under the AGPLv3 license, unless a different license for a
 * particular library is specified in the applicable library path.
 *
 * You may obtain a copy of the License at
 * https://www.gnu.org/licenses/agpl-3.0.html
 * ============================================================================
 */

package com.kaltura.playkit.plugins.youbora;

import android.text.TextUtils;

import com.kaltura.playkit.BuildConfig;
import com.kaltura.playkit.MessageBus;
import com.kaltura.playkit.PKError;
import com.kaltura.playkit.PKEvent;
import com.kaltura.playkit.PKLog;
import com.kaltura.playkit.PKMediaConfig;
import com.kaltura.playkit.PKMediaEntry;
import com.kaltura.playkit.PlayKitManager;
import com.kaltura.playkit.PlaybackInfo;
import com.kaltura.playkit.Player;
import com.kaltura.playkit.PlayerEvent;
import com.kaltura.playkit.plugins.ads.AdCuePoints;
import com.kaltura.playkit.plugins.ads.AdEvent;
import com.kaltura.playkit.plugins.youbora.pluginconfig.YouboraConfig;
import com.kaltura.playkit.utils.Consts;
import com.npaw.youbora.lib6.YouboraUtil;
import com.npaw.youbora.lib6.adapter.PlayerAdapter;

import java.util.LinkedHashSet;

import static com.kaltura.playkit.PlayerEvent.Type.PLAYHEAD_UPDATED;
import static com.kaltura.playkit.PlayerEvent.Type.STATE_CHANGED;
import static com.kaltura.playkit.plugins.ads.AdEvent.Type.AD_PROGRESS;

/**
 * @hide
 */

class PKYouboraPlayerAdapter extends PlayerAdapter<Player> {

    private static final PKLog log = PKLog.get("PKYouboraPlayerAdapter");
    private static final String KALTURA_ANDROID = "Kaltura-Android";
    private static final String PLAYER_ERROR_STR = "Player error occurred";

    private MessageBus messageBus;
    private PKMediaConfig mediaConfig;

    private boolean isFirstPlay = true;
    private boolean isBuffering = false;

    private String lastReportedResource = "unknown";
    private Long lastReportedBitrate = -1L;
    private Long lastReportedThroughput;
    private String lastReportedRendition;
    private Double lastReportedMediaPosition;
    private Double lastReportedMediaDuration;
    private String houseHoldId;
    private boolean isAdPlaying;
    private AdCuePoints adCuePoints;

    PKYouboraPlayerAdapter(Player player, MessageBus messageBus, PKMediaConfig mediaConfig, YouboraConfig pluginConfig) {
        super(player);
        log.d("Start PKYouboraPlayerAdapter");
        this.messageBus = messageBus;
        this.mediaConfig = mediaConfig;
        updateDurationFromMediaConfig(mediaConfig);
        this.houseHoldId = pluginConfig.getHouseHoldId();
        registerListeners();

    }

    private void updateDurationFromMediaConfig(PKMediaConfig mediaConfig) {
        if (mediaConfig != null && mediaConfig.getMediaEntry() != null) {
            lastReportedMediaDuration = Math.floor((double) mediaConfig.getMediaEntry().getDuration() / Consts.MILLISECONDS_MULTIPLIER);
        }
    }

    private void onEvent(PlayerEvent.StateChanged event) {
        //If it is first play, do not continue with the flow.
        if (isFirstPlay) {
            return;
        }

        switch (event.newState) {
            case READY:
                if (isBuffering) {
                    //log.d("fireBufferEnd");
                    isBuffering = false;
                    fireBufferEnd();
                }
                break;
            case BUFFERING:
                //log.d("fireBufferBegin");
                isBuffering = true;
                fireBufferBegin();
                break;
            default:
                break;
        }
        sendReportEvent(event);
    }


    private PKEvent.Listener mEventListener = new PKEvent.Listener() {
        @Override
        public void onEvent(PKEvent event) {

            if (event.eventType() == PlayerEvent.Type.PLAYBACK_INFO_UPDATED) {
                PlaybackInfo currentPlaybackInfo = ((PlayerEvent.PlaybackInfoUpdated) event).playbackInfo;
                lastReportedBitrate = Long.valueOf(currentPlaybackInfo.getVideoBitrate());
                lastReportedThroughput = Long.valueOf(currentPlaybackInfo.getVideoThroughput());
                lastReportedRendition = generateRendition(lastReportedBitrate, (int) currentPlaybackInfo.getVideoWidth(), (int) currentPlaybackInfo.getVideoHeight());
                return;
            }

            if (event instanceof PlayerEvent) {
                if (event.eventType() != PLAYHEAD_UPDATED) {
                    log.d("New PKEvent = " + event.eventType().name());
                }
                switch (((PlayerEvent) event).type) {
                    case DURATION_CHANGE:
                        lastReportedMediaDuration = Math.floor((double) ((PlayerEvent.DurationChanged) event).duration / Consts.MILLISECONDS_MULTIPLIER);
                        log.d("DURATION_CHANGE duration = " + lastReportedMediaDuration);
                        break;
                    case PLAYHEAD_UPDATED:
                        PlayerEvent.PlayheadUpdated playheadUpdated = (PlayerEvent.PlayheadUpdated) event;
                        lastReportedMediaPosition  = Math.floor((double) playheadUpdated.position / Consts.MILLISECONDS_MULTIPLIER);
                        //log.d("PLAYHEAD_UPDATED new duration = " + lastReportedMediaPosition);
                        break;
                    case STATE_CHANGED:
                        PKYouboraPlayerAdapter.this.onEvent((PlayerEvent.StateChanged) event);
                        break;
                    case ENDED:
                        if (!isFirstPlay && ((adCuePoints == null) || !adCuePoints.hasPostRoll())) {
                            fireStop();
                            isFirstPlay = true;
                            adCuePoints = null;
                        }
                        break;
                    case ERROR:
                        PKError error = ((PlayerEvent.Error) event).error;
                        if (error != null && !error.isFatal()) {
                            log.v("Error eventType = " + error.errorType + " severity = " + error.severity + " errorMessage = " + error.message);
                            return;
                        }
                        sendErrorHandler(event);
                        adCuePoints = null;
                        break;
                    case PAUSE:
                        firePause();
                        break;
                    case PLAY:
                        if (!isFirstPlay) {
                            fireResume();
                        } else {
                            isFirstPlay = false;
                            fireStart();
                        }
                        break;
                    case PLAYING:
                        if (isFirstPlay) {
                            isFirstPlay = false;
                            fireStart();
                        }
                        fireJoin();
                        break;
                    case SEEKED:
                        fireSeekEnd();
                        break;
                    case SEEKING:
                        fireSeekBegin();
                        break;
                    case SOURCE_SELECTED:
                        PlayerEvent.SourceSelected sourceSelected = ((PlayerEvent.SourceSelected) event);
                        lastReportedResource = sourceSelected.source.getUrl();
                        //log.d("SOURCE_SELECTED lastReportedResource = " + lastReportedResource);
                        break;
                    default:
                        break;
                }
                if (((PlayerEvent) event).type != STATE_CHANGED) {
                    sendReportEvent(event);
                }
            } else if (event instanceof AdEvent) {
                onAdEvent((AdEvent) event);
            }
        }
    };

    private void sendErrorHandler(PKEvent event) {

        PlayerEvent.Error errorEvent = (PlayerEvent.Error) event;
        String errorMetadata = (errorEvent != null && errorEvent.error != null) ? errorEvent.error.message : PLAYER_ERROR_STR;
        PKError error = errorEvent.error;
        if (error == null || error.exception == null) {
            fireFatalError(errorMetadata, event.eventType().name(), null);
            return;
        }

        Exception playerErrorException = (Exception) error.exception;
        String exceptionClass = "";

        if (playerErrorException.getCause() != null && playerErrorException.getCause().getClass() != null) {
            exceptionClass = playerErrorException.getCause().getClass().getName();
            errorMetadata = (playerErrorException.getCause().toString() != null) ? playerErrorException.getCause().toString() : errorMetadata;
        } else {
            if (error.exception.getClass() != null) {
                exceptionClass = error.exception.getClass().getName();
            }
        }

        LinkedHashSet<String> causeMessages = getExceptionMessageChain(playerErrorException);
        StringBuilder exceptionCauseBuilder = new StringBuilder();
        if (causeMessages.isEmpty()) {
            exceptionCauseBuilder.append(playerErrorException.toString());
        } else {
            for (String cause : causeMessages) {
                exceptionCauseBuilder.append(cause).append("\n");
            }
        }

        String errorCode = (errorEvent != null && errorEvent.error != null && errorEvent.error.errorType != null) ?  errorEvent.error.errorType + " - " : "";
        fireFatalError(exceptionCauseBuilder.toString(), errorCode + exceptionClass, errorMetadata);
    }

    public static LinkedHashSet<String> getExceptionMessageChain(Throwable throwable) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        while (throwable != null) {
            if (throwable.getMessage() != null){
                result.add(throwable.getMessage());
            }
            throwable = throwable.getCause();
        }
        return result;
    }

    private void onAdEvent(AdEvent event) {
        if (event.eventType() != AdEvent.Type.PLAY_HEAD_CHANGED && event.eventType() != PLAYHEAD_UPDATED && event.eventType() != AD_PROGRESS) {
            log.d("Ad Event: " + event.type.name());
        }

        switch (event.type) {
            case CONTENT_PAUSE_REQUESTED:
                isAdPlaying = true;
                break;
            case STARTED:
                isAdPlaying = true;
                break;
            case ERROR:
                isAdPlaying = false;
                break;
            case CONTENT_RESUME_REQUESTED:
                isAdPlaying = false;
                break;
            case CUEPOINTS_CHANGED:
                AdEvent.AdCuePointsUpdateEvent cuePointsList = (AdEvent.AdCuePointsUpdateEvent) event;
                adCuePoints = cuePointsList.cuePoints;
                break;
            case ALL_ADS_COMPLETED:
                if (adCuePoints != null && adCuePoints.hasPostRoll()) {
                    getPlugin().getAdapter().fireStop();
                    isFirstPlay = true;
                    adCuePoints = null;
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void registerListeners() {
        super.registerListeners();
        isFirstPlay = true;
        messageBus.listen(mEventListener, (Enum[]) PlayerEvent.Type.values());
        messageBus.listen(mEventListener, (Enum[]) AdEvent.Type.values());
    }

    @Override
    public void unregisterListeners() {
        messageBus.remove(mEventListener, (Enum[]) PlayerEvent.Type.values());
        messageBus.remove(mEventListener, (Enum[]) AdEvent.Type.values());
        super.unregisterListeners();
    }


    public Long getBitrate() {
        return this.lastReportedBitrate;
    }

    public Long getThroughput() {
        return this.lastReportedThroughput;
    }

    public String getRendition() {
        return lastReportedRendition;
    }

    public String getKalturaPlayerVersion() {
        return Consts.KALTURA + "-" + PlayKitManager.CLIENT_TAG;
    }

    @Override
    public String getVersion() {
        //getPluginVeriosn
        return com.npaw.youbora.lib6.BuildConfig.VERSION_NAME + "-" + BuildConfig.VERSION_NAME + "-" + getPlayerVersion();
    }

    @Override
    public String getPlayerVersion() {
        return Consts.KALTURA + "-" + PlayKitManager.CLIENT_TAG;
    }

    @Override
    public String getPlayerName() {
        return KALTURA_ANDROID;
    }

    @Override
    public String getHouseholdId() {
        return houseHoldId;
    }

    public Double getPlayhead() {
        return (lastReportedMediaPosition != null && lastReportedMediaPosition >= 0) ? lastReportedMediaPosition : 0;
    }

    public String getResource() {
        //log.d("getResource = " + lastReportedResource);
        return lastReportedResource;
    }

    @Override
    public Double getDuration() {
        //log.d("getDuration lastReportedMediaDuration = " + lastReportedMediaDuration);
        return lastReportedMediaDuration;
    }

//    public Double getPlayrate() {
//        return lastPlayrate
//    }

    public String getTitle() {
        if (mediaConfig == null || mediaConfig.getMediaEntry() == null) {
            return "unknown";
        } else {
            return (!TextUtils.isEmpty(mediaConfig.getMediaEntry().getName())) ? mediaConfig.getMediaEntry().getName() :  mediaConfig.getMediaEntry().getId();
        }
    }

    public Boolean getIsLive() {
        Boolean isLive = Boolean.FALSE;
        if (mediaConfig != null && (player == null || (player!= null && player.getDuration() <= 0))) {
            isLive = mediaConfig.getMediaEntry().getMediaType() == PKMediaEntry.MediaEntryType.Live;
        } else if (player != null) {
            isLive = player.isLive();
        }
        return isLive;
    }

    private void sendReportEvent(PKEvent event) {
        if (event.eventType() != PLAYHEAD_UPDATED) {
            String reportedEventName = event.eventType().name();
            messageBus.post(new YouboraEvent.YouboraReport(reportedEventName));
        }
    }

    public String generateRendition(double bitrate, int width, int height) {

        if ((width <= 0 || height <= 0) && bitrate <= 0) {
            return super.getRendition();
        } else {
            return YouboraUtil.buildRenditionString(width, height, bitrate);
        }
    }

    public void onUpdateConfig() {
        log.d("onUpdateConfig");
        resetValues();
    }

    public void resetValues() {
        lastReportedBitrate = super.getBitrate();
        lastReportedRendition = super.getRendition();
        lastReportedThroughput = super.getThroughput();
        mediaConfig = null;
        houseHoldId = null;
        isFirstPlay = true;
    }

    public void resetPlaybackValues() {
        lastReportedMediaDuration = super.getDuration();
        lastReportedMediaPosition =  super.getPlayhead();
        lastReportedResource = null;
        adCuePoints = null;
        resetValues();
    }

    public void setMediaConfig(PKMediaConfig mediaConfig) {
        this.mediaConfig = mediaConfig;
        updateDurationFromMediaConfig(mediaConfig);

    }

    public void setPluginConfig(YouboraConfig pluginConfig) {
        this.houseHoldId = pluginConfig.getHouseHoldId();
    }

    public void setLastReportedResource(String lastReportedResource) {
        this.lastReportedResource = lastReportedResource;
    }
}