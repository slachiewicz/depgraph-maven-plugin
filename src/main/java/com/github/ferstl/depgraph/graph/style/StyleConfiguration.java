/*
 * Copyright (c) 2014 - 2016 by Stefan Ferstl <st.ferstl@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.ferstl.depgraph.graph.style;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.ferstl.depgraph.dot.AttributeBuilder;
import com.github.ferstl.depgraph.graph.NodeResolution;
import com.github.ferstl.depgraph.graph.style.resource.StyleResource;
import static com.github.ferstl.depgraph.graph.NodeResolution.INCLUDED;

public class StyleConfiguration {

  private AbstractNode defaultNode = new Box();
  private final Edge defaultEdge = new Edge();
  private final Map<StyleKey, AbstractNode> nodeStyles = new LinkedHashMap<>();
  private final Map<String, Edge> edgeScopeStyles = new LinkedHashMap<>();
  private final Map<NodeResolution, Edge> edgeResolutionStyles = new LinkedHashMap<>();


  public static StyleConfiguration load(StyleResource mainConfig, StyleResource... overrides) {
    ObjectMapper mapper = createObjectMapper();

    ObjectReader reader = mapper.readerFor(StyleConfiguration.class);
    StyleConfiguration styleConfiguration = readConfig(reader, mainConfig);
    for (StyleResource override : overrides) {
      StyleConfiguration overrideConfig = readConfig(reader, override);
      styleConfiguration.merge(overrideConfig);
    }

    return styleConfiguration;
  }

  private static ObjectMapper createObjectMapper() {
    SimpleModule module = new SimpleModule()
        .addKeySerializer(NodeResolution.class, new NodeResolutionSerializer())
        .addDeserializer(NodeResolution.class, new NodeResolutionDeserializer());

    ObjectMapper mapper = new ObjectMapper()
        .registerModule(module)
        .setPropertyNamingStrategy(PropertyNamingStrategy.KEBAB_CASE)
        .setSerializationInclusion(Include.NON_EMPTY)
        .setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
    return mapper;
  }

  private static StyleConfiguration readConfig(ObjectReader reader, StyleResource config) {
    try (InputStream is = config.openStream()) {
      return reader.readValue(is);
    } catch (JsonProcessingException e) {
      String message = String.format("Unable to read style configuration %s.\nLocation: line %s, column %s\nDetails: %s",
          config, e.getLocation().getLineNr(), e.getLocation().getColumnNr(), e.getOriginalMessage());

      throw new RuntimeException(message);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public AttributeBuilder defaultNodeAttributes() {
    return this.defaultNode.createAttributes();
  }

  public AttributeBuilder defaultEdgeAttributes() {
    return this.defaultEdge.createAttributes();
  }

  public AttributeBuilder edgeAttributes(NodeResolution resolution, String targetScope) {
    Edge edge = this.edgeResolutionStyles.get(resolution);

    // Scope style win over INCLUDED node resolution
    if (resolution == INCLUDED && this.edgeScopeStyles.containsKey(targetScope)) {
      edge = this.edgeScopeStyles.get(targetScope);
    }

    return edge != null ? edge.createAttributes() : new AttributeBuilder();
  }

  public AttributeBuilder nodeAttributes(String groupId, String artifactId, String version, String type, String scopes, String effectiveScope) {
    StyleKey artifactKey = StyleKey.create(groupId, artifactId, effectiveScope, type, version);
    AbstractNode node = this.defaultNode;

    for (Entry<StyleKey, AbstractNode> entry : this.nodeStyles.entrySet()) {
      StyleKey styleKey = entry.getKey();
      if (styleKey.matches(artifactKey)) {
        node = entry.getValue();
        break;
      }
    }

    return node.createAttributes(groupId, artifactId, version, scopes, node != this.defaultNode);
  }

  public String toJson() {
    ObjectMapper mapper = createObjectMapper();
    try {
      StringWriter w = new StringWriter();
      mapper.writerWithDefaultPrettyPrinter().writeValue(w, this);
      return w.toString();
    } catch (IOException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  private void merge(StyleConfiguration other) {
    // We have to deal with subclasses here. Hence the double merge.
    this.defaultNode.merge(other.defaultNode);
    other.defaultNode.merge(this.defaultNode);
    this.defaultNode = other.defaultNode;

    this.defaultEdge.merge(other.defaultEdge);

    for (Entry<StyleKey, AbstractNode> entry : other.nodeStyles.entrySet()) {
      StyleKey styleKey = entry.getKey();
      AbstractNode node = entry.getValue();

      if (this.nodeStyles.containsKey(styleKey)) {
        AbstractNode originalNode = this.nodeStyles.get(styleKey);
        // Double merge again
        originalNode.merge(node);
        node.merge(originalNode);
        this.nodeStyles.put(styleKey, node);
      } else {
        this.nodeStyles.put(styleKey, node);
      }
    }

    for (Entry<NodeResolution, Edge> entry : other.edgeResolutionStyles.entrySet()) {
      NodeResolution resolution = entry.getKey();
      Edge edge = entry.getValue();

      if (this.edgeResolutionStyles.containsKey(resolution)) {
        this.edgeResolutionStyles.get(resolution).merge(edge);
      } else {
        this.edgeResolutionStyles.put(resolution, edge);
      }
    }

    for (Entry<String, Edge> entry : other.edgeScopeStyles.entrySet()) {
      String scope = entry.getKey();
      Edge edge = entry.getValue();

      if (this.edgeScopeStyles.containsKey(scope)) {
        this.edgeScopeStyles.get(scope).merge(edge);
      } else {
        this.edgeScopeStyles.put(scope, edge);
      }
    }
  }

}
