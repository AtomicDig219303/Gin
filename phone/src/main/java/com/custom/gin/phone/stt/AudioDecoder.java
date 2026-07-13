package com.custom.gin.phone.stt;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioDecoder {
    private static final String TAG = "AudioDecoder";

    public static byte[] decodeAmrToPcm(File amrFile) throws IOException {
        Log.d(TAG, "Decoding AMR file to PCM: " + amrFile.getAbsolutePath());
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(amrFile.getAbsolutePath());
        } catch (IOException e) {
            extractor.release();
            throw e;
        }

        int trackIndex = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                trackIndex = i;
                break;
            }
        }

        if (trackIndex == -1) {
            extractor.release();
            throw new IOException("No audio track found in the 3GP/AMR file");
        }

        MediaFormat format = extractor.getTrackFormat(trackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);
        
        MediaCodec decoder;
        try {
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();
        } catch (Exception e) {
            extractor.release();
            throw new IOException("Failed to create/configure MediaCodec decoder: " + e.getMessage());
        }

        ByteBuffer[] inputBuffers = decoder.getInputBuffers();
        ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteArrayOutputStream pcmOutputStream = new ByteArrayOutputStream();

        boolean isInputEOS = false;
        boolean isOutputEOS = false;
        long timeoutUs = 10000;

        try {
            while (!isOutputEOS) {
                if (!isInputEOS) {
                    int inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isInputEOS = true;
                        } else {
                            decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputBufferIndex = decoder.dequeueOutputBuffer(info, timeoutUs);
                if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                    
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true;
                    }

                    int size = info.size;
                    if (size > 0) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + size);
                        byte[] chunk = new byte[size];
                        outputBuffer.get(chunk);
                        pcmOutputStream.write(chunk);
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = decoder.getOutputBuffers();
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    Log.d(TAG, "Decoder output format changed: " + newFormat);
                }
            }
        } finally {
            try {
                decoder.stop();
            } catch (Exception e) {
                // Ignore
            }
            decoder.release();
            extractor.release();
        }

        byte[] decodedBytes = pcmOutputStream.toByteArray();
        Log.d(TAG, "Decoding completed. Decoded " + decodedBytes.length + " bytes of PCM.");
        return decodedBytes;
    }
}
