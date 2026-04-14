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
package prerna.semoss.web.services.local;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and strips SEMOSS context metadata (insightId, roomId) from prompts.
 * 
 * Expected format: [[SEMOSS_CONTEXT:insightId=xxx,roomId=yyy]]
 * The tag is removed so it never reaches the downstream model.
 */
public class SemossContextExtractor {

    private static final Pattern CONTEXT_PATTERN = Pattern.compile(
        "\\[\\[SEMOSS_CONTEXT:(.*?)\\]\\]\\s*"
    );

    private static final Pattern PARENT_ROOM_PATTERN = Pattern.compile(
        "\\[\\[PARENT_ROOM_ID=(.*?)\\]\\]\\s*"
    );

    private static final Pattern KV_PATTERN = Pattern.compile(
        "(\\w+)=([^,\\]]+)"
    );

    public static class ExtractionResult {
        private final String insightId;
        private final String roomId;

        public ExtractionResult(String insightId, String roomId) {
            this.insightId = insightId;
            this.roomId = roomId;
        }

        public String getInsightId() {
            return insightId;
        }

        public String getRoomId() {
            return roomId;
        }

        public boolean hasInsightId() {
            return insightId != null && !insightId.isEmpty();
        }

        public boolean hasRoomId() {
            return roomId != null && !roomId.isEmpty();
        }
    }

    /**
     * Extract SEMOSS context from a raw string and return the cleaned string
     * plus any extracted IDs.
     */
    public static ExtractionResult extract(String text) {
        if (text == null || text.isEmpty()) {
            return new ExtractionResult(null, null);
        }

        Matcher contextMatcher = CONTEXT_PATTERN.matcher(text);
        if (!contextMatcher.find()) {
            return new ExtractionResult(null, null);
        }

        String tagContent = contextMatcher.group(1);
        Map<String, String> kvMap = new HashMap<>();
        Matcher kvMatcher = KV_PATTERN.matcher(tagContent);
        while (kvMatcher.find()) {
            kvMap.put(kvMatcher.group(1), kvMatcher.group(2).trim());
        }

        return new ExtractionResult(
            kvMap.get("insightId"),
            kvMap.get("roomId")
        );
    }

    /**
     * Strip the SEMOSS_CONTEXT tag from a string, returning the cleaned version.
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return CONTEXT_PATTERN.matcher(text).replaceAll("").trim();
    }

    /**
     * Extract context from the latest message in the Anthropic messages list,
     * and strip the tag from the message content in-place.
     *
     * Handles both simple string content:
     *   {"role": "user", "content": "[[SEMOSS_CONTEXT:...]] hello"}
     *
     * And structured content block arrays:
     *   {"role": "user", "content": [{"type": "text", "text": "[[SEMOSS_CONTEXT:...]] hello"}, ...]}
     *
     * @param message the last message map from the messages list (mutated in place)
     * @return ExtractionResult with any found IDs
     */
    @SuppressWarnings("unchecked")
    public static ExtractionResult extractAndStripFromMessage(Map<String, Object> message) {
        if (message == null) {
            return new ExtractionResult(null, null);
        }

        Object content = message.get("content");

        // Case 1: content is a plain string
        if (content instanceof String) {
            String text = (String) content;
            ExtractionResult result = extract(text);
            if (result.hasInsightId() || result.hasRoomId()) {
                message.put("content", strip(text));
            }
            return result;
        }

        // Case 2: content is a list of blocks (Anthropic structured format)
        if (content instanceof List) {
            List<Object> blocks = (List<Object>) content;
            for (Object block : blocks) {
                if (block instanceof Map) {
                    Map<String, Object> blockMap = (Map<String, Object>) block;
                    if ("text".equals(blockMap.get("type")) && blockMap.get("text") instanceof String) {
                        String text = (String) blockMap.get("text");
                        ExtractionResult result = extract(text);
                        if (result.hasInsightId() || result.hasRoomId()) {
                            blockMap.put("text", strip(text));
                            return result;
                        }
                    }
                }
            }
        }

        return new ExtractionResult(null, null);
    }

    /**
     * Extract the parent room ID from a [[PARENT_ROOM_ID=...]] tag in the system prompt.
     *
     * @param systemPrompt the full system prompt string
     * @return the parent room ID, or null if no tag is present
     */
    public static String extractParentRoomId(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return null;
        }
        Matcher matcher = PARENT_ROOM_PATTERN.matcher(systemPrompt);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Strip the [[PARENT_ROOM_ID=...]] tag from a string.
     */
    public static String stripParentRoomTag(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return PARENT_ROOM_PATTERN.matcher(text).replaceAll("").trim();
    }
}