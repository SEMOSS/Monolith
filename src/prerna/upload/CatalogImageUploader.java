/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.core.FileUploadSizeException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletDiskFileUpload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.util.concurrent.Striped;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import prerna.auth.User;
import prerna.auth.utils.SecurityEngineUtils;
import prerna.auth.utils.SecurityProjectUtils;
import prerna.cluster.util.ClusterUtil;
import prerna.engine.api.IEngine;
import prerna.io.connector.couch.CouchUtil;
import prerna.semoss.web.services.local.ResourceUtility;
import prerna.util.Constants;
import prerna.util.EngineUtility;
import prerna.util.Utility;
import prerna.web.services.util.WebUtility;

/**
 * Handles the resource-scoped catalog image uploads. The legacy /images routes
 * retain their existing contract in ImageUploader.
 */
public final class CatalogImageUploader {

	private static final Logger classLogger = LogManager.getLogger(CatalogImageUploader.class);
	static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
	private static final long MAX_IMAGE_PIXELS = 25_000_000;
	private static final List<String> IMAGE_EXTENSIONS = List.of("png", "jpeg", "jpg", "gif", "svg");
	private static final Striped<Lock> IMAGE_LOCKS = Striped.lock(64);

	private CatalogImageUploader() {
	}

	/**
	 * Upload one multipart file for an engine or a project (including agents and
	 * skills). Authorize before parsing the request or touching image storage.
	 */
	public static Response upload(ServletContext context, HttpServletRequest request, String id, boolean project) {
		User user;
		try {
			user = ResourceUtility.getUser(request);
		} catch (IllegalAccessException e) {
			return error("User session is invalid", 401);
		}
		if (user.isAnonymous()) {
			return error("Must be logged in to upload an image", 401);
		}
		if (!WebUtility.isSafePathSegment(id)) {
			return error("Invalid " + (project ? "project" : "engine") + " id", 400);
		}

		List<DiskFileItem> items = List.of();
		try {
			boolean canEdit = project ? SecurityProjectUtils.userCanEditProject(user, id)
					: SecurityEngineUtils.userCanEditEngine(user, id);
			if (!canEdit) {
				return error("Resource does not exist or user does not have permission to edit it", 403);
			}
			IEngine.CATALOG_TYPE type = project ? IEngine.CATALOG_TYPE.PROJECT
					: SecurityEngineUtils.getEngineType(id);
			String name = project ? SecurityProjectUtils.getProjectAliasForId(id)
					: SecurityEngineUtils.getEngineAliasForId(id);

			if (!JakartaServletDiskFileUpload.isMultipartContent(request)) {
				return error("Use multipart/form-data with one file field named 'file'", 415);
			}
			items = parseRequest(context, request);
			if (items.size() != 1 || items.get(0).isFormField() || !"file".equals(items.get(0).getFieldName())) {
				return error("Provide exactly one image in the 'file' field", 400);
			}
			DiskFileItem item = items.get(0);
			String extension = validateImage(item);

			// A cluster cache pull refreshes the whole catalog folder. Serialize its
			// uploads so one resource's pull cannot overwrite another's pending image.
			Lock lock = IMAGE_LOCKS.get(ClusterUtil.IS_CLUSTER ? type : type + ":" + id);
			lock.lock();
			try {
				storeImage(id, name, type, item, extension);
			} finally {
				lock.unlock();
			}

			String downloadPath = project ? "/project-{id}/projectImage/download" : "/e-{id}/image/download";
			String imageUrl = UriBuilder.fromPath(request.getContextPath() + "/api" + downloadPath).build(id).toString();
			return WebUtility.getResponse(Map.of("message", "Successfully updated image", "id", id,
					"name", name, "imageUrl", imageUrl, "contentType", "image/" + extension), 200);
		} catch (WebApplicationException e) {
			return e.getResponse();
		} catch (FileUploadSizeException e) {
			return error("Upload exceeds the 10 MiB image limit or contains too many parts", 413);
		} catch (FileUploadException e) {
			return error("Invalid multipart image upload", 400);
		} catch (Exception e) {
			classLogger.error("Failed to upload image for {} {}", project ? "project" : "engine", id, e);
			return error("Unable to store image", 500);
		} finally {
			for (DiskFileItem item : items) {
				try {
					item.delete();
				} catch (IOException e) {
					classLogger.warn("Failed to remove temporary image upload", e);
				}
			}
		}
	}

	private static List<DiskFileItem> parseRequest(ServletContext context, HttpServletRequest request)
			throws IOException {
		String configuredTemp = context.getInitParameter(Uploader.TEMP_FILE_UPLOAD_KEY);
		Path temp = configuredTemp == null ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(configuredTemp);
		Files.createDirectories(temp);
		DiskFileItemFactory factory = DiskFileItemFactory.builder().setThreshold(8 * 1024).setPath(temp).get();
		JakartaServletDiskFileUpload upload = new JakartaServletDiskFileUpload(factory);
		upload.setHeaderCharset(StandardCharsets.UTF_8);
		upload.setMaxFileSize(MAX_IMAGE_BYTES);
		upload.setMaxSize(MAX_IMAGE_BYTES + 16 * 1024);
		upload.setMaxFileCount(1);
		upload.setMaxPartHeaderSize(8 * 1024);
		return upload.parseRequest(request);
	}

	/** Detect and decode the bytes; never derive a storage path from client MIME or filename. */
	static String validateImage(DiskFileItem item) throws IOException {
		if (item.getSize() == 0) {
			throw new WebApplicationException(error("Image file is empty", 400));
		}
		if (item.getSize() > MAX_IMAGE_BYTES) {
			throw new WebApplicationException(error("Image exceeds the 10 MiB limit", 413));
		}
		try (InputStream input = item.getInputStream();
				ImageInputStream image = new MemoryCacheImageInputStream(input)) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(image);
			if (!readers.hasNext()) {
				throw new WebApplicationException(error("Supported image formats are PNG, JPEG, and GIF", 415));
			}
			ImageReader reader = readers.next();
			try {
				String format = reader.getFormatName().toLowerCase(Locale.ROOT);
				if (!List.of("png", "jpeg", "gif").contains(format)) {
					throw new WebApplicationException(error("Supported image formats are PNG, JPEG, and GIF", 415));
				}
				reader.setInput(image, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
					throw new WebApplicationException(error("Image must not exceed 25 million pixels", 400));
				}
				if (reader.read(0) == null) {
					throw new WebApplicationException(error("Invalid image data", 400));
				}
				return format;
			} finally {
				reader.dispose();
			}
		} catch (IOException e) {
			throw new WebApplicationException(error("Invalid image data", 400));
		}
	}

	private static void storeImage(String id, String name, IEngine.CATALOG_TYPE type, DiskFileItem item,
			String extension) throws Exception {
		boolean project = type == IEngine.CATALOG_TYPE.PROJECT;
		// Load/pull the resource before changing its version folder.
		if (project) {
			Utility.getProject(id);
		} else {
			Utility.getEngine(id, type, true);
		}
		Path version = Path.of(EngineUtility.getSpecificEngineVersionFolder(type, id, name));
		List<Path> oldVersionImages = replaceImage(version, "image", item, extension);
		Path saved = version.resolve("image." + extension);
		if (ClusterUtil.IS_CLUSTER && !project) {
			ClusterUtil.copyLocalFileToEngineCloudFolder(id, type, saved.toString());
			for (Path old : oldVersionImages) {
				ClusterUtil.deleteEngineCloudFile(id, type, old.toString());
			}
		}
		if (CouchUtil.COUCH_ENABLED) {
			String selector = EngineUtility.getCouchSelector(type);
			CouchUtil.upload(selector, Map.of(selector, id), saved.toFile());
		} else if (ClusterUtil.IS_CLUSTER) {
			// Pull before replacing so images written by other nodes are included.
			ClusterUtil.pullEngineAndProjectImageFolder(type);
			Path images = Path.of(EngineUtility.getLocalEngineImageDirectory(type));
			List<Path> oldImages = replaceImage(images, id, item, extension);
			ClusterUtil.pushEngineAndProjectImage(type, id + "." + extension);
			for (Path old : oldImages) {
				ClusterUtil.deleteEngineAndProjectImage(type, old.getFileName().toString());
			}
		}
	}

	/** Stage the new bytes before replacing anything, then remove other image formats only. */
	static List<Path> replaceImage(Path directory, String basename, DiskFileItem item, String extension)
			throws IOException {
		Files.createDirectories(directory);
		Path target = directory.resolve(basename + "." + extension);
		Path staged = Files.createTempFile(directory, ".image-upload-", ".tmp");
		try {
			try (InputStream input = item.getInputStream()) {
				Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
			}
			try {
				Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(staged);
		}
		List<Path> removed = new ArrayList<>();
		for (String oldExtension : IMAGE_EXTENSIONS) {
			Path old = directory.resolve(basename + "." + oldExtension);
			if (!old.equals(target) && Files.deleteIfExists(old)) {
				removed.add(old);
			}
		}
		return removed;
	}

	private static Response error(String message, int status) {
		return WebUtility.getResponse(Map.of(Constants.ERROR_MESSAGE, message), status);
	}
}
