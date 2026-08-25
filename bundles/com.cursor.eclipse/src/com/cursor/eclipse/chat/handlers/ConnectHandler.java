package com.cursor.eclipse.chat.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

import com.cursor.eclipse.chat.ChatView;

public class ConnectHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		if (HandlerUtil.getActivePart(event) instanceof ChatView view) {
			view.connect();
		}
		return null;
	}
}
