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
