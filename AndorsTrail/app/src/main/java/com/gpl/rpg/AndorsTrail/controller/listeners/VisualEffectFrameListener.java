package com.gpl.rpg.AndorsTrail.controller.listeners;

import com.gpl.rpg.AndorsTrail.controller.VisualEffectController.SpriteMoveAnimation;
import com.gpl.rpg.AndorsTrail.controller.VisualEffectController.VisualEffectAnimation;
import com.gpl.rpg.AndorsTrail.util.CoordRect;

import java.util.List;

public interface VisualEffectFrameListener {
	void onNewAnimationFrames(List<VisualEffectAnimation> effects);
	void onAnimationCompleted(VisualEffectAnimation animation);
	void onSpriteMoveStarted(SpriteMoveAnimation animation);
	void onNewSpriteMoveFrame(SpriteMoveAnimation animation);
	void onSpriteMoveCompleted(SpriteMoveAnimation animation);
	void onAsyncAreaUpdate(CoordRect area);
}
