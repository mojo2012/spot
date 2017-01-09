package at.spot.jfly.demo;

import at.spot.jfly.Body;
import at.spot.jfly.ComponentController;
import at.spot.jfly.Head;
import at.spot.jfly.JFlyApplication;
import at.spot.jfly.event.JsEvent;
import at.spot.jfly.style.ButtonStyle;
import at.spot.jfly.style.LabelStyle;
import at.spot.jfly.ui.Badge;
import at.spot.jfly.ui.Button;
import at.spot.jfly.ui.Label;
import at.spot.jfly.ui.NavBar;
import j2html.TagCreator;

public class DemoApplication extends JFlyApplication {

	@Override
	protected Head createHeader() {
		final Head head = new Head().title("Hello world");

		return head;
	}

	@Override
	protected Body createBody() {
		final NavBar navBar = new NavBar();
		final Body body = new Body().addChild(navBar);
		final Button button = new Button("Say hello!").style(ButtonStyle.Success);
		final Button logoutButton = new Button("Logout").style(ButtonStyle.Success);

		navBar.addChild(button);
		navBar.addChild(logoutButton);
		navBar.addChild(new Label("test").style(LabelStyle.Danger));
		navBar.addChild(new Badge("42"));

		logoutButton.onEvent(JsEvent.click, e -> {
			ComponentController.instance().invokeFunctionCall("jfly", "reloadApp");
			ComponentController.instance().closeCurrentSession();
		});

		button.onEvent(JsEvent.click, e -> {
			body.addChild(TagCreator.h1("hello world"));
		});

		button.onEvent(JsEvent.mouseover, e -> {
			button.caption("over");
		});

		button.onEvent(JsEvent.mouseout, e -> {
			button.caption("and out");
		});

		return body;
	}
}
