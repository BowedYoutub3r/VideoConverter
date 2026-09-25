/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dstukalov.videoconverter;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringDef;
import androidx.annotation.WorkerThread;

import com.dstukalov.videoconverter.muxer.StreamingMuxer;

@SuppressWarnings("WeakerAccess")
public class MediaConverter {
    private static final String TAG = "media-converter";
    private static final boolean VERBOSE = false; // lots of logging

    // Describes when the annotation will be discarded
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({VIDEO_CODEC_H264, VIDEO_CODEC_H265, VIDEO_CODEC_VP9, VIDEO_CODEC_AV1})
    public @interface VideoCodec {}
    public static final String VIDEO_CODEC_H264 = "video/avc";
    public static final String VIDEO_CODEC_H265 = "video/hevc";
    public static final String VIDEO_CODEC_VP9 = "video/x-vnd.on2.vp9";
    public static final String VIDEO_CODEC_AV1 = "video/av01";

    private Input mInput;
    private Output mOutput;

    private long mTimeFrom;
    private long mTimeTo;
    private int mVideoResolution;
    private int mVideoBitrate = 2000000; // 2Mbps
    private int mVideoBitrateMode = -1; // see MediaCodecInfo.EncoderCapabilities
    private @VideoCodec String mVideoCodec = VIDEO_CODEC_H264;
    private int mAudioBitrate = 128000; // 128Kbps

    private Listener mListener;
    private boolean mCancelled;
    private final List<AudioTrackMetadata> mAudioTracks = new ArrayList<>();

    public static final class AudioTrackMetadata {
        private final int sourceTrackIndex;
        private final int trackNumber;
        private final String title;
        private final String language;
        private final int bitrate;

        public AudioTrackMetadata(final int sourceTrackIndex,
                                 final int trackNumber,
                                 final @Nullable String title,
                                 final @Nullable String language,
                                 final int bitrate) {
            this.sourceTrackIndex = sourceTrackIndex;
            this.trackNumber = trackNumber;
            this.title = title;
            this.language = language;
            this.bitrate = bitrate;
        }

        public int getSourceTrackIndex() {
            return sourceTrackIndex;
        }

        public int getTrackNumber() {
            return trackNumber;
        }

        public @Nullable String getTitle() {
            return title;
        }

        public @Nullable String getLanguage() {
            return language;
        }

        public int getBitrate() {
            return bitrate;
        }
    }

    public interface Listener {
        boolean onProgress(int percent);

        default boolean onProgress(int percent, long elapsedMillis, long estimatedRemainingMillis) {
            return onProgress(percent);
        }

        default boolean onProgress(int percent, long elapsedMillis, long estimatedRemainingMillis, @NonNull String stage) {
            return onProgress(percent, elapsedMillis, estimatedRemainingMillis);
        }
    }

    public MediaConverter() {
    }

    @SuppressWarnings("unused")
    public void setInput(final @NonNull File file) {
        mInput = new FileInput(file);
    }

    @SuppressWarnings("unused")
    public void setInput(final @NonNull Context context, final @NonNull Uri uri) {
        mInput = new UriInput(context, uri);
    }

    @SuppressWarnings("unused")
    @RequiresApi(23)
    public void setInput(final @NonNull MediaDataSource mediaDataSource) {
        mInput = new MediaDataSourceInput(mediaDataSource);
    }

    @SuppressWarnings("unused")
    public void setOutput(final @NonNull File file) {
        mOutput = new FileOutput(file);
    }

    @SuppressWarnings("unused")
    @RequiresApi(26)
    public void setOutput(final @NonNull FileDescriptor fileDescriptor) {
        mOutput = new FileDescriptorOutput(fileDescriptor);
    }

    @SuppressWarnings("unused")
    public void setOutput(final @NonNull OutputStream outputStream) {
        mOutput = new StreamOutput(outputStream);
    }

    @SuppressWarnings("unused")
    public void setTimeRange(long timeFrom, long timeTo) {
        mTimeFrom = timeFrom;
        mTimeTo = timeTo;

        if (timeTo > 0 && timeFrom >= timeTo) {
            throw new IllegalArgumentException("timeFrom:" + timeFrom + " timeTo:" + timeTo);
        }
    }

    @SuppressWarnings("unused")
    public void setVideoResolution(int videoResolution) {
        mVideoResolution = videoResolution;
    }

    @SuppressWarnings("unused")
    public void setVideoCodec(final @VideoCodec String videoCodec) throws FileNotFoundException {
        if (selectCodec(videoCodec) == null) {
            throw new FileNotFoundException();
        }
        mVideoCodec = videoCodec;
    }

    @SuppressWarnings("unused")
    public void setVideoBitrate(final int videoBitrate) {
        mVideoBitrate = videoBitrate;
    }

    @SuppressWarnings("unused")
    public void setVideoBitrateMode(final int videoBitrateMode) {
        mVideoBitrateMode = videoBitrateMode;
    }

    @SuppressWarnings("unused")
    public void setAudioBitrate(final int audioBitrate) {
        mAudioBitrate = audioBitrate;
    }

    @SuppressWarnings("unused")
    public void setAudioTrackMetadata(final @NonNull List<AudioTrackMetadata> tracks) {
        mAudioTracks.clear();
        if (tracks != null) {
            mAudioTracks.addAll(tracks);
        }
    }

    @SuppressWarnings("unused")
    public void setAudioTrackMetadata(final int sourceTrackIndex,
                                     final int trackNumber,
                                     final @Nullable String title,
                                     final @Nullable String language,
                                     final int bitrate) {
        final AudioTrackMetadata metadata = new AudioTrackMetadata(sourceTrackIndex, trackNumber, title, language, bitrate);
        for (int i = 0; i < mAudioTracks.size(); i++) {
            if (mAudioTracks.get(i).getSourceTrackIndex() == sourceTrackIndex) {
                mAudioTracks.set(i, metadata);
                return;
            }
        }
        mAudioTracks.add(metadata);
    }

    @SuppressWarnings("unused")
    public void setListener(final Listener listener) {
        mListener = listener;
    }

    /**
     * Returns the video codecs that this device can encode. The result is immutable.
     */
    public static @NonNull List<String> getAvailableVideoCodecs() {
        final List<String> codecs = new ArrayList<>();
        final String[] candidates = {
                VIDEO_CODEC_H264,
                VIDEO_CODEC_H265,
                VIDEO_CODEC_VP9,
                VIDEO_CODEC_AV1
        };
        for (String candidate : candidates) {
            if (selectCodec(candidate) != null) {
                codecs.add(candidate);
            }
        }
        return Collections.unmodifiableList(codecs);
    }

    public Muxer createMuxer() throws IOException {
        return mOutput.createMuxer();
    }

    @WorkerThread
    public void convert() throws BadMediaException, IOException, MediaConversionException {
        Exception exception = null;
        Muxer muxer = null;
        VideoTrackConverter videoTrackConverter = null;
        final List<AudioTrackConverter> audioTrackConverters = new ArrayList<>();

        try {
            videoTrackConverter = VideoTrackConverter.create(mInput, mTimeFrom, mTimeTo, mVideoResolution, mVideoBitrate, mVideoBitrateMode, mVideoCodec);
            audioTrackConverters.addAll(createAudioTrackConverters());

            if (videoTrackConverter == null && audioTrackConverters.isEmpty()) {
                Log.e(TAG, "no video and audio tracks");
                throw new BadMediaException();
            }

            muxer = createMuxer();
            doExtractDecodeEditEncodeMux(videoTrackConverter, audioTrackConverters, muxer);

        } catch (BadMediaException | IOException e) {
            Log.e(TAG, "error converting", e);
            exception = e;
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "error converting", e);
            exception = e;
        } finally {
            if (VERBOSE) Log.d(TAG, "releasing extractor, decoder, encoder, and muxer");
            try {
                if (videoTrackConverter != null) {
                    videoTrackConverter.release();
                }
            } catch (Exception e) {
                if (exception == null) {
                    exception = e;
                }
            }
            for (AudioTrackConverter audioTrackConverter : audioTrackConverters) {
                try {
                    if (audioTrackConverter != null) {
                        audioTrackConverter.release();
                    }
                } catch (Exception e) {
                    if (exception == null) {
                        exception = e;
                    }
                }
            }
            try {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing muxer", e);
                if (exception == null) {
                    exception = e;
                }
            }
        }
        if (exception != null) {
            throw new MediaConversionException(exception);
        }
    }

    /**
     * Does the actual work for extracting, decoding, encoding and muxing.
     */
    private void doExtractDecodeEditEncodeMux(
            final @Nullable VideoTrackConverter videoTrackConverter,
            final @NonNull List<AudioTrackConverter> audioTrackConverters,
            final @NonNull Muxer muxer) throws IOException {

        boolean muxing = false;
        int percentProcessed = 0;
        final long conversionStartMillis = System.currentTimeMillis();
        long inputDuration = Math.max(
                videoTrackConverter == null ? 0 : videoTrackConverter.mInputDuration,
                audioTrackConverters.isEmpty() ? 0 : maxAudioDuration(audioTrackConverters));

        while (!mCancelled &&
                ((videoTrackConverter != null && !videoTrackConverter.mVideoEncoderDone) ||
                 hasActiveAudio(audioTrackConverters))) {

            final String stage = determineStage(videoTrackConverter, audioTrackConverters, muxing);

            if (VERBOSE) {
                Log.d(TAG, "loop: " +
                        (videoTrackConverter == null ? "" : videoTrackConverter.dumpState()) +
                        " muxing:" + muxing);
            }

            if (videoTrackConverter != null) {
                final long maxAudioMuxingTime = maxAudioMuxingTime(audioTrackConverters);
                final boolean shouldProcessVideo = audioTrackConverters.isEmpty()
                        || audioTrackConverters.stream().allMatch(audioTrackConverter ->
                            audioTrackConverter == null || audioTrackConverter.mAudioExtractorDone ||
                            videoTrackConverter.mMuxingVideoPresentationTime <= audioTrackConverter.mMuxingAudioPresentationTime)
                        || (maxAudioMuxingTime == 0 && videoTrackConverter.mMuxingVideoPresentationTime <= 0);
                if (shouldProcessVideo) {
                    videoTrackConverter.step();
                }
            }

            for (AudioTrackConverter audioTrackConverter : audioTrackConverters) {
                if (audioTrackConverter != null && !audioTrackConverter.mAudioEncoderDone) {
                    final boolean shouldProcessAudio = videoTrackConverter == null
                            || videoTrackConverter.mVideoExtractorDone
                            || videoTrackConverter.mMuxingVideoPresentationTime >= audioTrackConverter.mMuxingAudioPresentationTime;
                    if (shouldProcessAudio) {
                        audioTrackConverter.step();
                    }
                }
            }

            if (inputDuration != 0 && mListener != null) {
                final long timeFromUs = mTimeFrom <= 0 ? 0 : mTimeFrom * 1000;
                final long timeToUs = mTimeTo <= 0 ? inputDuration : mTimeTo * 1000;
                final long currentMuxingTime = Math.max(
                        videoTrackConverter == null ? 0 : videoTrackConverter.mMuxingVideoPresentationTime,
                        maxAudioMuxingTime(audioTrackConverters));
                final int curPercentProcessed = (int) (100 *
                        (currentMuxingTime - timeFromUs) / (timeToUs - timeFromUs));

                if (curPercentProcessed != percentProcessed) {
                    percentProcessed = curPercentProcessed;
                    final long elapsedMillis = System.currentTimeMillis() - conversionStartMillis;
                    final long estimatedRemainingMillis = percentProcessed <= 0
                        ? -1
                        : elapsedMillis * (100L - percentProcessed) / percentProcessed;
                    mCancelled = mCancelled || mListener.onProgress(
                        percentProcessed,
                        elapsedMillis,
                        estimatedRemainingMillis,
                        stage);
                }
            }

            if (!muxing
                    && (videoTrackConverter == null || videoTrackConverter.mEncoderOutputVideoFormat != null)
                    && audioTrackConverters.stream().allMatch(audioTrackConverter -> audioTrackConverter == null || audioTrackConverter.mEncoderOutputAudioFormat != null)) {
                if (videoTrackConverter != null) {
                    videoTrackConverter.setMuxer(muxer);
                }
                for (AudioTrackConverter audioTrackConverter : audioTrackConverters) {
                    if (audioTrackConverter != null) {
                        audioTrackConverter.setMuxer(muxer);
                    }
                }
                Log.d(TAG, "muxer: starting");
                muxer.start();
                muxing = true;
            }
        }

        if (videoTrackConverter != null) {
            videoTrackConverter.verifyEndState();
        }
        for (AudioTrackConverter audioTrackConverter : audioTrackConverters) {
            if (audioTrackConverter != null) {
                audioTrackConverter.verifyEndState();
            }
        }
    }

    private @NonNull List<AudioTrackConverter> createAudioTrackConverters() throws IOException {
        final List<AudioTrackConverter> converters = new ArrayList<>();
        final MediaExtractor probeExtractor = mInput.createExtractor();
        try {
            for (int index = 0; index < probeExtractor.getTrackCount(); index++) {
                final MediaFormat trackFormat = probeExtractor.getTrackFormat(index);
                if (!isAudioFormat(trackFormat)) {
                    continue;
                }
                final MediaExtractor extractor = mInput.createExtractor();
                extractor.selectTrack(index);
                final AudioTrackMetadata metadata = resolveAudioTrackMetadata(index, trackFormat);
                converters.add(AudioTrackConverter.create(extractor, index, mTimeFrom, mTimeTo, mAudioBitrate, metadata));
            }
        } finally {
            probeExtractor.release();
        }
        return converters;
    }

    private @NonNull AudioTrackMetadata resolveAudioTrackMetadata(final int sourceTrackIndex,
                                                                final @NonNull MediaFormat trackFormat) {
        for (AudioTrackMetadata metadata : mAudioTracks) {
            if (metadata.getSourceTrackIndex() == sourceTrackIndex) {
                return metadata;
            }
        }
        final String title = trackFormat.containsKey(MediaFormat.KEY_TITLE)
                ? trackFormat.getString(MediaFormat.KEY_TITLE)
                : null;
        final String language = trackFormat.containsKey(MediaFormat.KEY_LANGUAGE)
                ? trackFormat.getString(MediaFormat.KEY_LANGUAGE)
                : null;
        final int bitrate = trackFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                ? trackFormat.getInteger(MediaFormat.KEY_BIT_RATE)
                : mAudioBitrate;
        return new AudioTrackMetadata(sourceTrackIndex, sourceTrackIndex + 1, title, language, bitrate);
    }

    private static boolean hasActiveAudio(final @NonNull List<AudioTrackConverter> audioTrackConverters) {
        for (AudioTrackConverter audioTrackConverter : audioTrackConverters) {
            if (audioTrackConverter != null && !audioTrackConverter.mAudioEncoderDone) {
                return true;
            }
        }
        return false;
    }

    private static long maxAudioDuration(final @NonNull List<AudioTrackConverter> audioTrackConverters) {
        long maxDuration = 0;
        for (AudioTrackConverter audioTrackConverter : audioTrackConverters) {
            if (audioTrackConverter != null) {
                maxDuration = Math.max(maxDuration, audioTrackConverter.mInputDuration);
            }
        }
        return maxDuration;
    }

    private static long maxAudioMuxingTime(final @NonNull List<AudioTrackConverter> audioTrackConverters) {
        long maxMuxingTime = 0;
        for (AudioTrackConverter audioTrackConverter : audioTrackConverters) {
            if (audioTrackConverter != null) {
                maxMuxingTime = Math.max(maxMuxingTime, audioTrackConverter.mMuxingAudioPresentationTime);
            }
        }
        return maxMuxingTime;
    }

    private @NonNull String determineStage(
            final @Nullable VideoTrackConverter videoTrackConverter,
            final @NonNull List<AudioTrackConverter> audioTrackConverters,
            final boolean muxing) {
        if (videoTrackConverter == null && audioTrackConverters.isEmpty()) {
            return "preparing";
        }
        if (!muxing) {
            return "transcoding";
        }
        if (videoTrackConverter != null && !audioTrackConverters.isEmpty()) {
            return "muxing";
        }
        return "finalizing";
    }

    static String getMimeTypeFor(MediaFormat format) {
        return format.getString(MediaFormat.KEY_MIME);
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no match was
     * found.
     */
    public static MediaCodecInfo selectCodec(final String mimeType) {
        final int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            final MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            final String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    interface Input {
        @NonNull MediaExtractor createExtractor() throws IOException;
    }

    private static class FileInput implements Input {

        final File file;

        FileInput(final @NonNull File file) {
            this.file = file;
        }

        @Override
        public @NonNull MediaExtractor createExtractor() throws IOException {
            final MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(file.getAbsolutePath());
            return extractor;
        }
    }

    private static class UriInput implements Input {

        final Uri uri;
        final Context context;

        UriInput(final @NonNull Context context, final @NonNull Uri uri) {
            this.uri = uri;
            this.context = context;
        }

        @Override
        public @NonNull MediaExtractor createExtractor() throws IOException {
            final MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(context, uri, null);
            return extractor;
        }
    }

    @RequiresApi(23)
    private static class MediaDataSourceInput implements Input {

        private final MediaDataSource mediaDataSource;

        MediaDataSourceInput(final @NonNull MediaDataSource mediaDataSource) {
            this.mediaDataSource = mediaDataSource;
        }

        @Override
        public @NonNull MediaExtractor createExtractor() throws IOException {
            final MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(mediaDataSource);
            return extractor;
        }
    }

    interface Output {
        @NonNull Muxer createMuxer() throws IOException;
    }

    private static class FileOutput implements Output {

        final File file;

        FileOutput(final @NonNull File file) {
            this.file = file;
        }

        @Override
        public @NonNull Muxer createMuxer() throws IOException {
            return new AndroidMuxer(file);
        }
    }

    @RequiresApi(26)
    private static class FileDescriptorOutput implements Output {

        final FileDescriptor fileDescriptor;

        FileDescriptorOutput(final @NonNull FileDescriptor fileDescriptor) {
            this.fileDescriptor = fileDescriptor;
        }

        @Override
        public @NonNull Muxer createMuxer() throws IOException {
            return new AndroidMuxer(fileDescriptor);
        }
    }

    private static class StreamOutput implements Output {

        final OutputStream outputStream;

        StreamOutput(final @NonNull OutputStream outputStream) {
            this.outputStream = outputStream;
        }


        @Override
        public @NonNull Muxer createMuxer() {
            return new StreamingMuxer(outputStream);
        }
    }
}
