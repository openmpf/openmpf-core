/******************************************************************************
 * NOTICE                                                                     *
 *                                                                            *
 * This software (or technical data) was produced for the U.S. Government     *
 * under contract, and is subject to the Rights in Data-General Clause        *
 * 52.227-14, Alt. IV (DEC 2007).                                             *
 *                                                                            *
 * Copyright 2021 The MITRE Corporation. All Rights Reserved.                 *
 ******************************************************************************/

/******************************************************************************
 * Copyright 2021 The MITRE Corporation                                       *
 *                                                                            *
 * Licensed under the Apache License, Version 2.0 (the "License");            *
 * you may not use this file except in compliance with the License.           *
 * You may obtain a copy of the License at                                    *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 * Unless required by applicable law or agreed to in writing, software        *
 * distributed under the License is distributed on an "AS IS" BASIS,          *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   *
 * See the License for the specific language governing permissions and        *
 * limitations under the License.                                             *
 ******************************************************************************/

package org.mitre.mpf.interop;

import com.fasterxml.jackson.annotation.*;

import java.util.Comparator;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.mitre.mpf.interop.util.CompareUtils.sortedSetCompare;

@JsonTypeName("TypeOutputObject")
public class JsonActionOutputObject implements Comparable<JsonActionOutputObject> {

    public static final String NO_TRACKS_TYPE = "NO TRACKS";
    public static final String TRACKS_SUPPRESSED_TYPE = "TRACKS SUPPRESSED";
    public static final String TRACKS_MERGED_TYPE = "TRACKS MERGED FORWARD";

    @JsonProperty("source")
    @JsonPropertyDescription("The action source.")
    private String source;
    public String getSource() { return source; }

    @JsonProperty("algorithm")
    @JsonPropertyDescription("The action algorithm.")
    private String algorithm;
    public String getAlgorithm() { return algorithm; }

    @JsonProperty("tracks")
    @JsonPropertyDescription("The set of object detection tracks produced in this action for the given medium.")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private SortedSet<JsonTrackOutputObject> tracks;
    public SortedSet<JsonTrackOutputObject> getTracks() { return tracks; }

    public JsonActionOutputObject(String source, String algorithm) {
        this.source = source;
        this.algorithm = algorithm;
        this.tracks = new TreeSet<>();
    }

    public JsonActionOutputObject(){}

    @JsonCreator
    public static JsonActionOutputObject factory(@JsonProperty("source") String source,
                                                 @JsonProperty("algorithm") String algorithm,
                                                 @JsonProperty("tracks") SortedSet<JsonTrackOutputObject> tracks) {
        JsonActionOutputObject trackOutputObject = new JsonActionOutputObject(source, algorithm);
        if(tracks != null) {
            trackOutputObject.tracks.addAll(tracks);
        }
        return trackOutputObject;
    }

    public int hashCode() {
        return Objects.hash(source, algorithm, tracks);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof JsonActionOutputObject
                && compareTo((JsonActionOutputObject) other) == 0);
    }


    private static final Comparator<JsonActionOutputObject> DEFAULT_COMPARATOR = Comparator
            .nullsFirst(Comparator
                    .comparing(JsonActionOutputObject::getSource)
                    .thenComparing(JsonActionOutputObject::getAlgorithm)
                    .thenComparing(sortedSetCompare(JsonActionOutputObject::getTracks))
            );

    @Override
    public int compareTo(JsonActionOutputObject other) {
        return DEFAULT_COMPARATOR.compare(this, other);
    }
}