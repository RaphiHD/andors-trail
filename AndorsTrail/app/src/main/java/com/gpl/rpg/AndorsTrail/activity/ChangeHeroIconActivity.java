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

		changeheroicon_title = (TextView) findViewById(R.id.changeheroicon_title);
		changeheroicon_description = (TextView) findViewById(R.id.changeheroicon_description);
		
		Button b;

		b = (Button) findViewById(R.id.hero_sprite_1);
		b.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				player.updatePlayerIcon(0); // TODO: Find out actual IDs of Hero Sprites
			}
		});

		b = (Button) findViewById(R.id.hero_sprite_2);
		b.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				player.updatePlayerIcon(1); // TODO: Find out actual IDs of Hero Sprites
			}
		});

		b = (Button) findViewById(R.id.hero_sprite_3);
		b.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				player.updatePlayerIcon(2); // TODO: Find out actual IDs of Hero Sprites
			}
		});

		b = (Button) findViewById(R.id.changeheroicon_close);
		b.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				ChangeHeroIconActivity.this.finish();
			}
		});
	}
}
