package com.finflow.studio.assistant;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FixedToolCallTest {
    final ObjectMapper json = new ObjectMapper();
    @Test void resourceContentCannotMasqueradeAsAnUnresolvedBinding() {
        var resolved=FixedToolCall.bind(Map.of("$input","params","as","json"),Map.of("params","{\"$input\":\"other\"}"),json);
        assertThatThrownBy(()->new FixedToolCall("research.discover",1,Map.of()).validateArguments(Map.of("topic",resolved)))
                .hasMessageContaining("topic");
        assertThatThrownBy(()->FixedToolCall.bind(Map.of("$input","params","as","json"),Map.of("params","not json"),json))
                .hasMessageContaining("params").hasMessageContaining("有效JSON");
    }
    @Test void invalidPointerOrTextFieldBindingFailsAtPublicationParsing() {
        for (var binding : List.of(Map.of("$input","params","as","text","path","/topic"),
                Map.of("$input","params","as","json","path","topic"),
                Map.of("$input","params","as","json","path","/bad~escape"))) {
            var step = new FixedWorkDefinition.Step("s","Search","TOOL",json.writeValueAsString(
                    Map.of("tool","research.discover","version",1,"arguments",Map.of("topic",binding))),
                    "result.json",List.of(new FixedWorkDefinition.Input("params","file",null,null)));
            assertThatThrownBy(() -> FixedToolCall.parse(json,step)).hasMessageContaining("JSON Pointer");
        }
    }
    @Test void pointerPreservesTypesAndSupportsEscapedKeysAndArrays() {
        var input = Map.of("params", "{\"topic\":\"news\",\"maxSources\":5,\"a/b\":[true]}");
        assertThat(FixedToolCall.bind(Map.of("$input","params","as","json","path","/topic"),input,json)).isEqualTo("news");
        assertThat(FixedToolCall.bind(Map.of("$input","params","as","json","path","/maxSources"),input,json)).isEqualTo(5);
        assertThat(FixedToolCall.bind(Map.of("$input","params","as","json","path","/a~1b/0"),input,json)).isEqualTo(true);
        assertThatThrownBy(() -> FixedToolCall.bind(Map.of("$input","params","as","json","path","/missing"),input,json)).hasMessageContaining("缺少字段");
        assertThatThrownBy(() -> FixedToolCall.bind(Map.of("$input","params","as","json","path","/topic"),Map.of("params","{\"topic\":null}"),json)).hasMessageContaining("缺少字段");
    }
    @Test void validatesBoundTypesLengthsAndWholeJsonMistake() {
        var call = new FixedToolCall("research.discover",1,Map.of());
        for (var topic : List.of("x".repeat(501),Map.of("topic","news"),"{\"topic\":\"news\"}",""))
            assertThatThrownBy(() -> call.validateArguments(Map.of("topic",topic))).isInstanceOf(IllegalArgumentException.class);
        for (var limit : List.of("5",2,21,5.5))
            assertThatThrownBy(() -> call.validateArguments(Map.of("topic","news","maxSources",limit))).isInstanceOf(IllegalArgumentException.class);
        call.validateArguments(Map.of("topic","x".repeat(500),"maxSources",20));
    }
    @Test void partialBindingKeepsUnknownUpstreamButChecksKnownSiblings() {
        var args = Map.of("topic",Map.of("$input","upstream","as","json","path","/topic"),"maxSources",Map.of("$input","params","as","json","path","/n"));
        @SuppressWarnings("unchecked") var resolved=(Map<String,Object>) FixedToolCall.bind(args,Map.of("params","{\"n\":99}"),json,true);
        assertThatThrownBy(() -> new FixedToolCall("research.discover",1,argsAsMap(args)).validateArguments(resolved)).hasMessageContaining("maxSources");
    }
    private Map<String,Object> argsAsMap(Map<String,?> args) { return new HashMap<>(args); }
    @Test void validatesRenderCompatibilityAndStructure() {
        var call = new FixedToolCall("document.render",1,Map.of());
        var doc=Map.of("sections",List.of(Map.of("heading","Title")));
        call.validateArguments(Map.of("format","pptx","pptSkill","blue-professional-v2","document",doc));
        call.validateArguments(Map.of("format","docx","document",doc));
        assertThatThrownBy(() -> call.validateArguments(Map.of("format","docx","pptSkill","blue-professional-v2","document",doc))).hasMessageContaining("不兼容");
        assertThatThrownBy(() -> call.validateArguments(Map.of("format","pdf","document",Map.of("sections",List.of())))).hasMessageContaining("章节");
    }
}
