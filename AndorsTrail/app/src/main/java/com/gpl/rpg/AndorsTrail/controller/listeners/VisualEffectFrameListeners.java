package com.gpl.rpg.AndorsTrail.controller.listeners;

import com.gpl.rpg.AndorsTrail.controller.VisualEffectController.SpriteMoveAnimation;
import com.gpl.rpg.AndorsTrail.controller.VisualEffectController.VisualEffectAnimation;
import com.gpl.rpg.AndorsTrail.util.CoordRect;
import com.gpl.rpg.AndorsTrail.util.ListOfListeners;

import java.util.List;

public final class VisualEffectFrameListeners extends ListOfListeners<VisualEffectFrameListener> implements VisualEffectFrameListener {

	private final Function1<VisualEffectFrameListener, List<VisualEffectAnimation>> onNewAnimationFrames = new Function1<VisualEffectFrameListener, List<VisualEffectAnimation>>() {
		@Override public void call(VisualEffectFrameListener listener, List<VisualEffectAnimation> effects) { listener.onNewAnimationFrames(effects); }
	};

	private final Function1<VisualEffectFrameListener, VisualEffectAnimation> onAnimationCompleted = new Function1<VisualEffectFrameListener, VisualEffectAnimation>() {
		@Override public void call(VisualEffectFrameListener listener, VisualEffectAnimation animation) { listener.onAnimationCompleted(animation); }
	};
	
	private final Function1<VisualEffectFrameListener, SpriteMoveAnimation> onSpriteMoveStarted = new Function1<VisualEffectFrameListener, SpriteMoveAnimation>() {
		@Override public void call(VisualEffectFrameListener listener, SpriteMoveAnimation animation) { listener.onSpriteMoveStarted(animation); }
	};
	
	private final Function1<VisualEffectFrameListener, SpriteMoveAnimation> onNewSpriteMoveFrame = new Function1<VisualEffectFrameListener, SpriteMoveAnimation>() {
		@Override public void call(VisualEffectFrameListener listener, SpriteMoveAnimation animation) { listener.onNewSpriteMoveFrame(animation); }
	};
	
	private final Function1<VisualEffectFrameListener, SpriteMoveAnimation> onSpriteMoveCompleted = new Function1<VisualEffectFrameListener, SpriteMoveAnimation>() {
		@Override public void call(VisualEffectFrameListener listener, SpriteMoveAnimation animation) { listener.onSpriteMoveCompleted(animation); }
	};
	
	private final Function1<VisualEffectFrameListener, CoordRect> onAsyncAreaUpdate = new Function1<VisualEffectFrameListener, CoordRect>() {
		@Override public void call(VisualEffectFrameListener listener, CoordRect area) { listener.onAsyncAreaUpdate(area); }
	};

	@Override
	public void onNewAnimationFrames(List<VisualEffectAnimation> effects) {
		callAllListeners(this.onNewAnimationFrames, effects);
	}

	@Override
	public void onAnimationCompleted(VisualEffectAnimation animation) {
		callAllListeners(this.onAnimationCompleted, animation);
	}
	
	@Override
	public void onSpriteMoveStarted(SpriteMoveAnimation animation) {
		callAllListeners(this.onSpriteMoveStarted, animation);
	}
	
	@Override
	public void onNewSpriteMoveFrame(SpriteMoveAnimation animation) {
		callAllListeners(this.onNewSpriteMoveFrame, animation);
	}
	
	@Override
	public void onSpriteMoveCompleted(SpriteMoveAnimation animation) {
		callAllListeners(this.onSpriteMoveCompleted, animation);
	}
	
	@Override
	public void onAsyncAreaUpdate(CoordRect area) {
		callAllListeners(this.onAsyncAreaUpdate, area);
	}
}
