package com.gpl.rpg.AndorsTrail.activity;

import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import com.gpl.rpg.AndorsTrail.AndorsTrailApplication;
import com.gpl.rpg.AndorsTrail.R;
import com.gpl.rpg.AndorsTrail.context.ControllerContext;
import com.gpl.rpg.AndorsTrail.context.WorldContext;
import com.gpl.rpg.AndorsTrail.controller.ActorStatsController;
import com.gpl.rpg.AndorsTrail.controller.Constants;
import com.gpl.rpg.AndorsTrail.model.actor.Player;
import com.gpl.rpg.AndorsTrail.util.ThemeHelper;

public final class ChangeHeroIconActivity extends AndorsTrailBaseActivity {
  private WorldContext world;
  private ControllerContext controllers;
	private Player player;
	private TextView selecticon_description;
	private TextView selecticon_title;

	@Override
	public void onCreate(Bundle savedInstanceState) {
    setTheme(ThemeHelper.getDialogTheme());
		super.onCreate(savedInstanceState);
		AndorsTrailApplication app = AndorsTrailApplication.getApplicationFromActivity(this);
		if (!app.isInitialized()) { finish(); return; }
		this.world = app.getWorld();
		this.controllers = app.getControllerContext();
		this.player = world.model.player;

		requestWindowFeature(Window.FEATURE_NO_TITLE);

		setContentView(R.layout.levelup);



    
	}
}
