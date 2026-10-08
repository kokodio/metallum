package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLRenderPipelineColorAttachmentDescriptor extends NSObject {
    private static final Msg SET_PIXEL_FORMAT = Msg.ofVoid("setPixelFormat:", JAVA_LONG);
    private static final Msg SET_BLENDING_ENABLED = Msg.ofVoid("setBlendingEnabled:", JAVA_BOOLEAN);
    private static final Msg SET_SOURCE_RGB_BLEND_FACTOR = Msg.ofVoid("setSourceRGBBlendFactor:", JAVA_LONG);
    private static final Msg SET_DESTINATION_RGB_BLEND_FACTOR = Msg.ofVoid("setDestinationRGBBlendFactor:", JAVA_LONG);
    private static final Msg SET_RGB_BLEND_OPERATION = Msg.ofVoid("setRgbBlendOperation:", JAVA_LONG);
    private static final Msg SET_SOURCE_ALPHA_BLEND_FACTOR = Msg.ofVoid("setSourceAlphaBlendFactor:", JAVA_LONG);
    private static final Msg SET_DESTINATION_ALPHA_BLEND_FACTOR = Msg.ofVoid("setDestinationAlphaBlendFactor:", JAVA_LONG);
    private static final Msg SET_ALPHA_BLEND_OPERATION = Msg.ofVoid("setAlphaBlendOperation:", JAVA_LONG);
    private static final Msg SET_WRITE_MASK = Msg.ofVoid("setWriteMask:", JAVA_LONG);

    public MTLRenderPipelineColorAttachmentDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public void setPixelFormat(final MTLPixelFormat pixelFormat) {
        SET_PIXEL_FORMAT.send(this.handle, pixelFormat.value);
    }

    public void setBlendingEnabled(final boolean blendingEnabled) {
        SET_BLENDING_ENABLED.send(this.handle, blendingEnabled);
    }

    public void setSourceRGBBlendFactor(final MTLBlendFactor sourceRGBBlendFactor) {
        SET_SOURCE_RGB_BLEND_FACTOR.send(this.handle, sourceRGBBlendFactor.value);
    }

    public void setDestinationRGBBlendFactor(final MTLBlendFactor destinationRGBBlendFactor) {
        SET_DESTINATION_RGB_BLEND_FACTOR.send(this.handle, destinationRGBBlendFactor.value);
    }

    public void setRgbBlendOperation(final MTLBlendOperation rgbBlendOperation) {
        SET_RGB_BLEND_OPERATION.send(this.handle, rgbBlendOperation.value);
    }

    public void setSourceAlphaBlendFactor(final MTLBlendFactor sourceAlphaBlendFactor) {
        SET_SOURCE_ALPHA_BLEND_FACTOR.send(this.handle, sourceAlphaBlendFactor.value);
    }

    public void setDestinationAlphaBlendFactor(final MTLBlendFactor destinationAlphaBlendFactor) {
        SET_DESTINATION_ALPHA_BLEND_FACTOR.send(this.handle, destinationAlphaBlendFactor.value);
    }

    public void setAlphaBlendOperation(final MTLBlendOperation alphaBlendOperation) {
        SET_ALPHA_BLEND_OPERATION.send(this.handle, alphaBlendOperation.value);
    }

    public void setWriteMask(final long writeMask) {
        SET_WRITE_MASK.send(this.handle, writeMask);
    }
}
