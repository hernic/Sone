/*
 * Sone - EditProfileFieldPage.java - Copyright © 2011 David Roden
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.pterodactylus.sone.web;

import net.pterodactylus.sone.data.Profile;
import net.pterodactylus.sone.data.Sone;
import net.pterodactylus.sone.web.page.Page.Request.Method;
import net.pterodactylus.util.number.Numbers;
import net.pterodactylus.util.template.DataProvider;
import net.pterodactylus.util.template.Template;

/**
 * Page that lets the user edit the name of a profile field.
 *
 * @author <a href="mailto:bombe@pterodactylus.net">David ‘Bombe’ Roden</a>
 */
public class EditProfileFieldPage extends SoneTemplatePage {

	/**
	 * Creates a new “edit profile field” page.
	 *
	 * @param template
	 *            The template to render
	 * @param webInterface
	 *            The Sone web interface
	 */
	public EditProfileFieldPage(Template template, WebInterface webInterface) {
		super("editProfileField.html", template, "Page.EditProfileField.Title", webInterface, true);
	}

	//
	// SONETEMPLATEPAGE METHODS
	//

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void processTemplate(Request request, DataProvider dataProvider) throws RedirectException {
		super.processTemplate(request, dataProvider);
		Sone currentSone = getCurrentSone(request.getToadletContext());
		Profile profile = currentSone.getProfile();

		/* get parameters from request. */
		int fieldIndex = Numbers.safeParseInteger(request.getHttpRequest().getParam("field"), -1);
		if (fieldIndex >= currentSone.getProfile().getFieldNames().size()) {
			fieldIndex = -1;
		}
		String fieldName = null;
		String fieldValue = null;
		if (fieldIndex > -1) {
			fieldName = profile.getFieldNames().get(fieldIndex);
			fieldValue = profile.getField(fieldName);
		}

		/* process the POST request. */
		if (request.getMethod() == Method.POST) {
			if (request.getHttpRequest().getPartAsStringFailsafe("cancel", 4).equals("true")) {
				throw new RedirectException("editProfile.html#profile-fields");
			}
			fieldIndex = Numbers.safeParseInteger(request.getHttpRequest().getPartAsStringFailsafe("field", 11), -1);
			String name = request.getHttpRequest().getPartAsStringFailsafe("name", 256);
			if (fieldIndex == -1) {
				throw new RedirectException("invalid.html");
			}
			int existingFieldIndex = profile.getFieldNames().indexOf(name);
			if ((existingFieldIndex == -1) || (existingFieldIndex == fieldIndex)) {
				profile.setFieldName(fieldIndex, name);
				currentSone.setProfile(profile);
				throw new RedirectException("editProfile.html#profile-fields");
			}
			dataProvider.set("duplicateFieldName", true);
		}

		/* store current values in template. */
		dataProvider.set("fieldIndex", fieldIndex);
		dataProvider.set("fieldName", fieldName);
		dataProvider.set("fieldValue", fieldValue);
	}

}
