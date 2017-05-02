package com.seu.magicfilter.filter.base;

import android.opengl.GLES20;
import android.opengl.GLES30;

import com.seu.magicfilter.R;
import com.seu.magicfilter.beautify.MagicJni;
import com.seu.magicfilter.camera.RecordHelper;
import com.seu.magicfilter.camera.interfaces.OnRecordListener;
import com.seu.magicfilter.filter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.OpenGlUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * PBO录制
 *
 * @author Created by jz on 2017/5/2 9:17
 */
public class MagicRecordFilter extends GPUImageFilter {

    private IntBuffer mPboIds;
    private int mPboSize;

    private int mRowStride;//默认32的倍数
    private int mPboIndex;
    private int mPboNewIndex;
    private long mLastTimestamp;//图像时间戳，用于录制帧数判断

    private boolean mRecordEnabled;
    private boolean mInitRecord;

    private RecordHelper mRecordHelper;

    public MagicRecordFilter() {
        super(R.raw.none_vertex, R.raw.default_fragment);
        mRecordHelper = new RecordHelper();
        setTextureTransformMatrix(new float[]{
                -1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                1f, 0f, 0f, 1f});
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyPixelBuffers();
    }

    //初始化2个pbo，交替使用
    public void initPixelBuffer(int width, int height) {
        if (mPboIds != null && (mInputWidth != width || mInputHeight != height)) {
            destroyPixelBuffers();
        }
        if (mPboIds != null) {
            return;
        }

        int pixelStride = 32;

        float num = width / (float) pixelStride;
        int wholeNum = (int) num;

        if (num == wholeNum) {
            mRowStride = wholeNum * pixelStride;
        } else {
            mRowStride = (wholeNum + 1) * pixelStride;
        }

        mPboSize = mRowStride * height * 4;

        mPboIds = IntBuffer.allocate(2);
        GLES30.glGenBuffers(2, mPboIds);

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, mPboIds.get(0));
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, mPboSize, null, GLES30.GL_STATIC_READ);

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, mPboIds.get(1));
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, mPboSize, null, GLES30.GL_STATIC_READ);

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
    }

    private void destroyPixelBuffers() {
        if (mPboIds != null) {
            GLES30.glDeleteBuffers(GLES30.GL_PIXEL_PACK_BUFFER, mPboIds);
            mPboIds = null;
        }
    }

    public void startRecord() {
        if (mRecordEnabled) {
            return;
        }
        mRecordEnabled = true;
        mInitRecord = true;
        mPboIndex = 0;
        mPboNewIndex = 1;

        mRecordHelper.start();
    }

    public void stopRecord() {
        if (!mRecordEnabled) {
            return;
        }
        mRecordEnabled = false;
        mRecordHelper.stop();
    }

    public boolean isRecording() {
        return mRecordEnabled;
    }

    public int onDrawToFbo(final int textureId, final FloatBuffer cubeBuffer,
                           final FloatBuffer textureBuffer, final long timestamp) {
        if (mFrameBuffers == null || mPboIds == null) {
            return OpenGlUtils.NO_TEXTURE;
        }
        if (!mRecordEnabled || !mIsInitialized) {
            return OpenGlUtils.NOT_INIT;
        }

        GLES20.glViewport(0, 0, mInputWidth, mInputHeight);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffers[0]);
        GLES20.glUseProgram(mGLProgramId);
        runPendingOnDrawTasks();

        cubeBuffer.position(0);
        GLES20.glVertexAttribPointer(mGLAttributePosition, 2, GLES20.GL_FLOAT, false, 0, cubeBuffer);
        GLES20.glEnableVertexAttribArray(mGLAttributePosition);
        textureBuffer.position(0);
        GLES20.glVertexAttribPointer(mGLAttributeTextureCoordinate, 2, GLES20.GL_FLOAT, false, 0,
                textureBuffer);
        GLES20.glEnableVertexAttribArray(mGLAttributeTextureCoordinate);
        GLES20.glUniformMatrix4fv(mTextureTransformMatrixLocation, 1, false, mTextureTransformMatrix, 0);

        if (textureId != OpenGlUtils.NO_TEXTURE) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(mGLUniformTexture, 0);
        }

        onDrawArraysPre();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(mGLAttributePosition);
        GLES20.glDisableVertexAttribArray(mGLAttributeTextureCoordinate);
        onDrawArraysAfter();

        bindPixelBuffer();
        mLastTimestamp = timestamp;

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mOutputWidth, mOutputHeight);
        return OpenGlUtils.ON_DRAWN;
    }

    private void bindPixelBuffer() {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, mPboIds.get(mPboIndex));
        MagicJni.glReadPixels(0, 0, mRowStride, mInputHeight, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE);

        if (mInitRecord) {//第一帧没有数据跳出
            unbindPixelBuffer();
            mInitRecord = false;
            return;
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, mPboIds.get(mPboNewIndex));

        //glMapBufferRange会等待DMA传输完成，所以需要交替使用pbo
        ByteBuffer byteBuffer = (ByteBuffer) GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, mPboSize, GLES30.GL_MAP_READ_BIT);

        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
        unbindPixelBuffer();

        mRecordHelper.onRecord(byteBuffer, mInputWidth, mInputHeight, mRowStride, mLastTimestamp);
    }

    //解绑pbo
    private void unbindPixelBuffer() {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);

        mPboIndex = (mPboIndex + 1) % 2;
        mPboNewIndex = (mPboNewIndex + 1) % 2;
    }

    public void setRecordListener(OnRecordListener l) {
        mRecordHelper.setOnRecordListener(l);
    }
}