package com.metallum.mtl;

import com.metallum.objc.Msg;
import com.metallum.objc.NSObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLVertexBufferLayoutDescriptor extends NSObject {
    private static final Msg SET_STRIDE = Msg.ofVoid("setStride:", JAVA_LONG);
    private static final Msg SET_STEP_FUNCTION = Msg.ofVoid("setStepFunction:", JAVA_LONG);
    private static final Msg SET_STEP_RATE = Msg.ofVoid("setStepRate:", JAVA_LONG);

    public MTLVertexBufferLayoutDescriptor(final MemorySegment handle) {
        super(handle);
    }

    public void setStride(final long stride) {
        SET_STRIDE.send(this.handle, stride);
    }

    public void setStepFunction(final MTLVertexStepFunction stepFunction) {
        SET_STEP_FUNCTION.send(this.handle, stepFunction.value);
    }

    public void setStepRate(final long stepRate) {
        SET_STEP_RATE.send(this.handle, stepRate);
    }
}
