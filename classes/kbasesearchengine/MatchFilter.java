
package kbasesearchengine;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Generated;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;


/**
 * <p>Original spec-file type: MatchFilter</p>
 * <pre>
 * Optional rules of defining constrains for object properties
 * including values of keywords or metadata/system properties (like
 * object name, creation time range) or full-text search in all
 * properties.
 * </pre>
 * 
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Generated("com.googlecode.jsonschema2pojo")
@JsonPropertyOrder({
    "full_text_in_all",
    "access_group_id",
    "object_name",
    "parent_guid",
    "timestamp",
    "lookupInKeys"
})
public class MatchFilter {

    @JsonProperty("full_text_in_all")
    private java.lang.String fullTextInAll;
    @JsonProperty("access_group_id")
    private Long accessGroupId;
    @JsonProperty("object_name")
    private java.lang.String objectName;
    @JsonProperty("parent_guid")
    private java.lang.String parentGuid;
    /**
     * <p>Original spec-file type: MatchValue</p>
     * <pre>
     * Optional rules of defining constraints for values of particular
     * term (keyword). Appropriate field depends on type of keyword.
     * For instance in case of integer type 'int_value' should be used.
     * In case of range constraint rather than single value 'min_*' 
     * and 'max_*' fields should be used. You may omit one of ends of
     * range to achieve '<=' or '>=' comparison. Ends are always
     * included for range constraints.
     * </pre>
     * 
     */
    @JsonProperty("timestamp")
    private kbasesearchengine.MatchValue timestamp;
    @JsonProperty("lookupInKeys")
    private Map<String, kbasesearchengine.MatchValue> lookupInKeys;
    private Map<java.lang.String, Object> additionalProperties = new HashMap<java.lang.String, Object>();

    @JsonProperty("full_text_in_all")
    public java.lang.String getFullTextInAll() {
        return fullTextInAll;
    }

    @JsonProperty("full_text_in_all")
    public void setFullTextInAll(java.lang.String fullTextInAll) {
        this.fullTextInAll = fullTextInAll;
    }

    public MatchFilter withFullTextInAll(java.lang.String fullTextInAll) {
        this.fullTextInAll = fullTextInAll;
        return this;
    }

    @JsonProperty("access_group_id")
    public Long getAccessGroupId() {
        return accessGroupId;
    }

    @JsonProperty("access_group_id")
    public void setAccessGroupId(Long accessGroupId) {
        this.accessGroupId = accessGroupId;
    }

    public MatchFilter withAccessGroupId(Long accessGroupId) {
        this.accessGroupId = accessGroupId;
        return this;
    }

    @JsonProperty("object_name")
    public java.lang.String getObjectName() {
        return objectName;
    }

    @JsonProperty("object_name")
    public void setObjectName(java.lang.String objectName) {
        this.objectName = objectName;
    }

    public MatchFilter withObjectName(java.lang.String objectName) {
        this.objectName = objectName;
        return this;
    }

    @JsonProperty("parent_guid")
    public java.lang.String getParentGuid() {
        return parentGuid;
    }

    @JsonProperty("parent_guid")
    public void setParentGuid(java.lang.String parentGuid) {
        this.parentGuid = parentGuid;
    }

    public MatchFilter withParentGuid(java.lang.String parentGuid) {
        this.parentGuid = parentGuid;
        return this;
    }

    /**
     * <p>Original spec-file type: MatchValue</p>
     * <pre>
     * Optional rules of defining constraints for values of particular
     * term (keyword). Appropriate field depends on type of keyword.
     * For instance in case of integer type 'int_value' should be used.
     * In case of range constraint rather than single value 'min_*' 
     * and 'max_*' fields should be used. You may omit one of ends of
     * range to achieve '<=' or '>=' comparison. Ends are always
     * included for range constraints.
     * </pre>
     * 
     */
    @JsonProperty("timestamp")
    public kbasesearchengine.MatchValue getTimestamp() {
        return timestamp;
    }

    /**
     * <p>Original spec-file type: MatchValue</p>
     * <pre>
     * Optional rules of defining constraints for values of particular
     * term (keyword). Appropriate field depends on type of keyword.
     * For instance in case of integer type 'int_value' should be used.
     * In case of range constraint rather than single value 'min_*' 
     * and 'max_*' fields should be used. You may omit one of ends of
     * range to achieve '<=' or '>=' comparison. Ends are always
     * included for range constraints.
     * </pre>
     * 
     */
    @JsonProperty("timestamp")
    public void setTimestamp(kbasesearchengine.MatchValue timestamp) {
        this.timestamp = timestamp;
    }

    public MatchFilter withTimestamp(kbasesearchengine.MatchValue timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    @JsonProperty("lookupInKeys")
    public Map<String, kbasesearchengine.MatchValue> getLookupInKeys() {
        return lookupInKeys;
    }

    @JsonProperty("lookupInKeys")
    public void setLookupInKeys(Map<String, kbasesearchengine.MatchValue> lookupInKeys) {
        this.lookupInKeys = lookupInKeys;
    }

    public MatchFilter withLookupInKeys(Map<String, kbasesearchengine.MatchValue> lookupInKeys) {
        this.lookupInKeys = lookupInKeys;
        return this;
    }

    @JsonAnyGetter
    public Map<java.lang.String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperties(java.lang.String name, Object value) {
        this.additionalProperties.put(name, value);
    }

    @Override
    public java.lang.String toString() {
        return ((((((((((((((("MatchFilter"+" [fullTextInAll=")+ fullTextInAll)+", accessGroupId=")+ accessGroupId)+", objectName=")+ objectName)+", parentGuid=")+ parentGuid)+", timestamp=")+ timestamp)+", lookupInKeys=")+ lookupInKeys)+", additionalProperties=")+ additionalProperties)+"]");
    }

}
