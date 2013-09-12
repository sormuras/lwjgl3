/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.system.glfw;

import java.util.concurrent.TimeUnit;

import com.lmax.disruptor.*;

import static java.lang.Double.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Wraps a WindowCallback to allow for asynchronous event notification. Events are queued from the NSApplication main thread and fired in the client thread that
 * calls {@link GLFW#glfwPollEvents} or {@link GLFW#glfwWaitEvents}.
 * <p/>
 * This implementantion uses an single-producer/single-consumer LMAX {@link RingBuffer} for passing events from the main thread to the client thread. It enables
 * lock-free synchronization, bounded batching and no runtime allocations. It also allows for customizable waiting strategies on {@link
 * GLFW#glfwWaitEvents}. Currently a phased-backoff strategy is used (spin, then yield, then sleep).
 *
 * @see <a href="http://github.com/LMAX-Exchange/disruptor">LMAX Exchange - Disruptor</a>
 */
final class WindowCallbackMacOSX extends WindowCallback {

	/** The ring-buffer size. */
	private static final int BUFFER_SIZE = 32;

	/** The event ring-buffer. */
	private static final RingBuffer<AsyncEvent> ringBuffer = RingBuffer.createSingleProducer(
		// Used to fill the ring-buffer with pre-allocated events.
		new EventFactory<AsyncEvent>() {
			@Override
			public AsyncEvent newInstance() {
				return new AsyncEvent();
			}
		},
		BUFFER_SIZE,
		// TODO: tune
		PhasedBackoffWaitStrategy.withSleep(1L, 1L, TimeUnit.MILLISECONDS)
		//new BlockingWaitStrategy()
	);

	/** Tracks the last published event. */
	private static final SequenceBarrier publishBarrier = ringBuffer.newBarrier();

	/** Tracks the last consumed event. */
	private static final Sequence consumeSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

	static {
		ringBuffer.addGatingSequences(consumeSequence);
	}

	private final WindowCallback target;

	WindowCallbackMacOSX(WindowCallback target) {
		this.target = target;
	}

	/**
	 * Publishes an event to the ring-buffer. This method is called from the main thread.
	 *
	 * @param target the target WindowCallback
	 * @param event  the event type
	 * @param window the window handle
	 * @param x      the first parameter
	 * @param y      the second parameter
	 */
	private static void offer(WindowCallback target, Event event, long window, long x, long y) {
		long next = ringBuffer.next();

		try {
			AsyncEvent asyncEvent = ringBuffer.get(next);

			asyncEvent.target = target;
			asyncEvent.event = event;
			asyncEvent.window = window;
			asyncEvent.a = x;
			asyncEvent.b = y;
		} finally {
			ringBuffer.publish(next);
		}
	}

	private static void offer(WindowCallback target, Event event, long window) {
		offer(target, event, window, NULL, NULL);
	}

	private static void offer(WindowCallback target, Event event, long window, long x) {
		offer(target, event, window, x, NULL);
	}

	@Override
	public void windowPos(long window, int xpos, int ypos) {
		offer(target, Event.WINDOW_POS, window, xpos, ypos);
	}

	@Override
	public void windowSize(long window, int width, int height) {
		offer(target, Event.WINDOW_SIZE, window, width, height);
	}

	@Override
	public void windowClose(long window) {
		offer(target, Event.WINDOW_CLOSE, window);
	}

	@Override
	public void windowRefresh(long window) {
		offer(target, Event.WINDOW_REFRESH, window);
	}

	@Override
	public void windowFocus(long window, int focused) {
		offer(target, Event.WINDOW_FOCUS, window, focused);
	}

	@Override
	public void windowIconify(long window, int iconified) {
		offer(target, Event.WINDOW_ICONIFY, window, iconified);
	}

	@Override
	public void framebufferSize(long window, int width, int height) {
		offer(target, Event.FRAMEBUFFER_SIZE, window, width, height);
	}

	@Override
	public void key(long window, int key, int scancode, int action, int mods) {
		long x = ((long)key << 32) | scancode;
		long y = ((long)action << 32) | mods;
		offer(target, Event.KEY, window, x, y);
	}

	@Override
	public void character(long window, int character) {
		offer(target, Event.CHARACTER, window, character);
	}

	@Override
	public void mouseButton(long window, int button, int action, int mods) {
		long x = button;
		long y = ((long)action << 32) | mods;
		offer(target, Event.MOUSE_BUTTON, window, x, y);
	}

	@Override
	public void cursorPos(long window, double xpos, double ypos) {
		long x = doubleToRawLongBits(xpos);
		long y = doubleToRawLongBits(ypos);
		offer(target, Event.CURSOR_POS, window, x, y);
	}

	@Override
	public void cursorEnter(long window, int entered) {
		offer(target, Event.CURSOR_ENTER, window, entered);
	}

	@Override
	public void scroll(long window, double xoffset, double yoffset) {
		long x = doubleToRawLongBits(xoffset);
		long y = doubleToRawLongBits(yoffset);
		offer(target, Event.SCROLL, window, x, y);
	}

	static void pollEvents() {
		long consumeNext = consumeSequence.get() + 1L;
		long consumeMax = publishBarrier.getCursor();

		// See if there's an event available
		if ( consumeNext <= consumeMax ) {
			do {
				// Fire in the current thread
				ringBuffer
					.get(consumeNext)
					.fire();

				// Keep firing until we reach consumeMax (batch-processing).
				consumeNext++;
			} while ( consumeNext <= consumeMax );

			// Let the ring-buffer know we're done processing this batch.
			consumeSequence.set(consumeMax);
		}
	}

	static void waitEvents() {
		try {
			// Block using the ring-buffer's wait strategy until at least one event is available.
			publishBarrier.waitFor(consumeSequence.get() + 1L);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		pollEvents();
	}

	/** Mutable event for use in the ring-buffer. Integer parameters are encoded as doubles. */
	private static class AsyncEvent {

		WindowCallback target;

		Event event;

		long window;

		long a;
		long b;

		static int hi(long v) { return (int)(v >>> 32); }

		static int lo(long v) { return (int)v; }

		void fire() {
			switch ( event ) {
				case WINDOW_POS:
					target.windowPos(window, (int)a, (int)b);
					break;
				case WINDOW_SIZE:
					target.windowSize(window, (int)a, (int)b);
					break;
				case WINDOW_CLOSE:
					target.windowClose(window);
					break;
				case WINDOW_REFRESH:
					target.windowRefresh(window);
					break;
				case WINDOW_FOCUS:
					target.windowFocus(window, (int)a);
					break;
				case WINDOW_ICONIFY:
					target.windowIconify(window, (int)a);
					break;
				case FRAMEBUFFER_SIZE:
					target.framebufferSize(window, (int)a, (int)b);
					break;
				case KEY:
					target.key(window, hi(a), lo(a), hi(b), lo(b));
					break;
				case CHARACTER:
					target.character(window, (int)a);
					break;
				case MOUSE_BUTTON:
					target.mouseButton(window, (int)a, hi(b), lo(b));
					break;
				case CURSOR_POS:
					target.cursorPos(window, longBitsToDouble(a), longBitsToDouble(b));
					break;
				case CURSOR_ENTER:
					target.cursorEnter(window, (int)a);
					break;
				case SCROLL:
					target.scroll(window, longBitsToDouble(a), longBitsToDouble(b));
					break;
				default:
					throw new IllegalStateException("Unsupported event type: " + event.name());
			}
		}
	}

}