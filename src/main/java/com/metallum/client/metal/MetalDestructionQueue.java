package com.metallum.client.metal;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalDestructionQueue {
	private final List<Runnable>[] queues;
	private int currentQueueIndex;

	@SuppressWarnings("unchecked")
	MetalDestructionQueue(final int queueCount) {
		this.queues = (List<Runnable>[])new List<?>[queueCount];
		for (int i = 0; i < queueCount; i++) {
			this.queues[i] = new ArrayList<>();
		}
	}

	void add(final Runnable destroyAction) {
		if (destroyAction == null) {
			return;
		}
		this.queues[this.currentQueueIndex].add(destroyAction);
	}

	void rotate() {
		this.currentQueueIndex = (this.currentQueueIndex + 1) % this.queues.length;
		List<Runnable> toDestroy = this.queues[this.currentQueueIndex];
		this.queues[this.currentQueueIndex] = new ArrayList<>();
		for (Runnable destroyAction : toDestroy) {
			destroyAction.run();
		}
	}

	void close() {
		for (int i = 0; i < this.queues.length; i++) {
			this.rotate();
		}
	}
}
