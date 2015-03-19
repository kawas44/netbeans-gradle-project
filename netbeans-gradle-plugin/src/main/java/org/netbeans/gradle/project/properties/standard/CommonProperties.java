package org.netbeans.gradle.project.properties.standard;

import org.jtrim.property.PropertyFactory;
import org.jtrim.property.PropertySource;
import org.netbeans.gradle.project.properties.ConfigTree;
import org.netbeans.gradle.project.properties.PropertyKeyEncodingDef;
import org.netbeans.gradle.project.properties.PropertyValueDef;
import org.netbeans.gradle.project.properties.ValueMerger;
import org.netbeans.gradle.project.properties.ValueReference;

public final class CommonProperties {
    @SuppressWarnings("unchecked")
    public static <T> PropertyValueDef<T, T> getIdentityValueDef() {
        return (PropertyValueDef<T, T>)IdentityValueDef.INSTANCE;
    }

    @SuppressWarnings("unchecked")
    public static <T> ValueMerger<T> getParentIfNullValueMerger() {
        return (ValueMerger<T>)ParentIfNullValueMerger.INSTANCE;
    }

    public static PropertyKeyEncodingDef<String> getIdentityKeyEncodingDef() {
        return IdentityKeyEncodingDef.INSTANCE;
    }

    public static PropertyKeyEncodingDef<ConfigTree> getIdentityTreeKeyEncodingDef() {
        return IdentityTreeKeyEncodingDef.INSTANCE;
    }

    private enum IdentityTreeKeyEncodingDef implements PropertyKeyEncodingDef<ConfigTree> {
        INSTANCE;

        @Override
        public ConfigTree decode(ConfigTree config) {
            return config;
        }

        @Override
        public ConfigTree encode(ConfigTree value) {
            return value;
        }
    }

    private enum ParentIfNullValueMerger implements ValueMerger<Object> {
        INSTANCE;

        @Override
        public Object mergeValues(Object child, ValueReference<Object> parent) {
            return child != null ? child : parent.getValue();
        }
    }

    private enum IdentityValueDef implements PropertyValueDef<Object, Object> {
        INSTANCE;

        @Override
        public PropertySource<Object> property(Object valueKey) {
            return PropertyFactory.constSource(valueKey);
        }

        @Override
        public Object getKeyFromValue(Object value) {
            return value;
        }
    }

    private enum IdentityKeyEncodingDef implements PropertyKeyEncodingDef<String> {
        INSTANCE;

        @Override
        public String decode(ConfigTree config) {
            return config.getValue(null);
        }

        @Override
        public ConfigTree encode(String value) {
            return ConfigTree.singleValue(value);
        }
    }

    private CommonProperties() {
        throw new AssertionError();
    }
}