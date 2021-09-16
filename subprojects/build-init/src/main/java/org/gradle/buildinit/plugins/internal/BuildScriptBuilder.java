/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.buildinit.plugins.internal;

import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.plugins.JvmTestSuitePlugin;
import org.gradle.api.plugins.jvm.JvmTestSuite;
import org.gradle.buildinit.InsecureProtocolOption;
import org.gradle.buildinit.plugins.internal.modifiers.BuildInitDsl;
import org.gradle.internal.Cast;
import org.gradle.internal.UncheckedException;
import org.gradle.util.internal.GFileUtils;
import org.gradle.util.internal.GUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Assembles the parts of a build script.
 */
@SuppressWarnings("UnusedReturnValue")
public class BuildScriptBuilder {
    private static final String INCUBATING_APIS_WARNING = "This project uses @Incubating APIs which are subject to change.";

    private static final Logger LOGGER = LoggerFactory.getLogger(BuildScriptBuilder.class);

    private final BuildInitDsl dsl;
    private final String fileNameWithoutExtension;
    private boolean externalComments;
    private final MavenRepositoryURLHandler mavenRepoURLHandler;

    private final List<String> headerLines = new ArrayList<>();
    private final TopLevelBlock block;

    private final boolean useIncubatingAPIs;
    private final boolean useTestSuites;

    BuildScriptBuilder(BuildInitDsl dsl, DocumentationRegistry documentationRegistry, String fileNameWithoutExtension, boolean useIncubatingAPIs, InsecureProtocolOption insecureProtocolOption) {
        this.dsl = dsl;
        this.fileNameWithoutExtension = fileNameWithoutExtension;
        this.useIncubatingAPIs = useIncubatingAPIs;
        this.useTestSuites = useIncubatingAPIs;
        this.mavenRepoURLHandler = MavenRepositoryURLHandler.forInsecureProtocolOption(insecureProtocolOption, dsl, documentationRegistry);
        this.block = new TopLevelBlock(this);
    }

    public BuildScriptBuilder withExternalComments() {
        this.externalComments = true;
        return this;
    }

    public boolean isUsingIncubatingAPIs() {
        return useIncubatingAPIs;
    }

    public boolean isUsingTestSuites() {
        return useTestSuites;
    }

    public String getFileNameWithoutExtension() {
        return fileNameWithoutExtension;
    }

    public static String getIncubatingApisWarning() {
        return INCUBATING_APIS_WARNING;
    }

    /**
     * Adds a comment to the header of the file.
     */
    public BuildScriptBuilder fileComment(String comment) {
        headerLines.addAll(splitComment(comment));
        return this;
    }

    public List<SuiteSpec> getSuites() {
        return new ArrayList<>(block.testing.suites);
    }

    private static List<String> splitComment(String comment) {
        return Splitter.on("\n").splitToList(comment.trim());
    }

    private static URI uriFromString(String uriAsString) {
        try {
            return new URI(uriAsString);
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    /**
     * Adds a plugin to be applied
     *
     * @param comment A description of why the plugin is required
     */
    public BuildScriptBuilder plugin(@Nullable String comment, String pluginId) {
        block.plugins.add(new PluginSpec(pluginId, null, comment));
        return this;
    }

    /**
     * Adds the plugin and config needed to support writing pre-compiled script plugins in the selected DSL in this project.
     */
    public BuildScriptBuilder conventionPluginSupport(@Nullable String comment) {
        Syntax syntax = syntaxFor(dsl);
        block.repositories.gradlePluginPortal("Use the plugin portal to apply community plugins in convention plugins.");
        syntax.configureConventionPlugin(comment, block.plugins, block.repositories);
        return this;
    }

    /**
     * Adds a plugin to be applied
     *
     * @param comment A description of why the plugin is required
     */
    public BuildScriptBuilder plugin(@Nullable String comment, String pluginId, String version) {
        block.plugins.add(new PluginSpec(pluginId, version, comment));
        return this;
    }

    /**
     * Adds one or more external dependencies to the specified configuration.
     *
     * @param configuration The configuration where the dependency should be added
     * @param comment A description of why the dependencies are required
     * @param dependencies the dependencies, in string notation
     */
    public BuildScriptBuilder dependency(String configuration, @Nullable String comment, String... dependencies) {
        dependencies().dependency(configuration, comment, dependencies);
        return this;
    }

    public void dependencyForSuite(String suite, String configuration, String comment, String... dependencies) {
        final SuiteSpec targetSuite = block.testing.suites.stream().filter((SuiteSpec s) -> s.getName().equals(suite)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown suite: " + suite));
        targetSuite.dependencies.dependency(configuration, comment, dependencies);
    }

    /**
     * Adds one or more external implementation dependencies.
     *
     * @param comment A description of why the dependencies are required
     * @param dependencies The dependencies, in string notation
     */
    public BuildScriptBuilder implementationDependency(@Nullable String comment, String... dependencies) {
        return dependency("implementation", comment, dependencies);
    }

    /**
     * Adds one or more dependency constraints to the implementation scope.
     *
     * @param comment A description of why the constraints are required
     * @param dependencies The constraints, in string notation
     */
    public BuildScriptBuilder implementationDependencyConstraint(@Nullable String comment, String... dependencies) {
        dependencies().dependencyConstraint("implementation", comment, dependencies);
        return this;
    }

    /**
     * Adds one or more external test implementation dependencies.
     *
     * @param comment A description of why the dependencies are required
     * @param dependencies The dependencies, in string notation
     */
    public BuildScriptBuilder testImplementationDependency(@Nullable String comment, String... dependencies) {
        return dependency("testImplementation", comment, dependencies);
    }

    /**
     * Adds one or more external test runtime only dependencies.
     *
     * @param comment A description of why the dependencies are required
     * @param dependencies The dependencies, in string notation
     */
    public BuildScriptBuilder testRuntimeOnlyDependency(@Nullable String comment, String... dependencies) {
        return dependency("testRuntimeOnly", comment, dependencies);
    }

    /**
     * Creates a method invocation expression, to use as a method argument or the RHS of a property assignment.
     */
    public Expression methodInvocationExpression(String methodName, Object... methodArgs) {
        return new MethodInvocationExpression(null, methodName, expressionValues(methodArgs));
    }

    /**
     * Creates a property expression, to use as a method argument or the RHS of a property assignment.
     */
    public Expression propertyExpression(String value) {
        return new LiteralValue(value);
    }

    /**
     * Creates a property expression, to use as a method argument or the RHS of a property assignment.
     */
    public Expression propertyExpression(Expression expression, String value) {
        return new ChainedPropertyExpression(expressionValue(expression), new LiteralValue(value));
    }

    /**
     * Creates an expression that references an element in a container.
     */
    public Expression containerElementExpression(String container, String element) {
        return new ContainerElementExpression(container, element);
    }

    private static List<ExpressionValue> expressionValues(Object... expressions) {
        List<ExpressionValue> result = new ArrayList<>(expressions.length);
        for (Object expression : expressions) {
            result.add(expressionValue(expression));
        }
        return result;
    }

    private static Map<String, ExpressionValue> expressionMap(Map<String, ?> expressions) {
        LinkedHashMap<String, ExpressionValue> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : expressions.entrySet()) {
            result.put(entry.getKey(), expressionValue(entry.getValue()));
        }
        return result;
    }

    private static ExpressionValue expressionValue(Object expression) {
        if (expression instanceof CharSequence) {
            return new StringValue((CharSequence) expression);
        }
        if (expression instanceof ExpressionValue) {
            return (ExpressionValue) expression;
        }
        if (expression instanceof Number || expression instanceof Boolean) {
            return new LiteralValue(expression);
        }
        if (expression instanceof Map) {
            return new MapLiteralValue(expressionMap(Cast.uncheckedNonnullCast(expression)));
        }
        if (expression instanceof Enum) {
            return new EnumValue(expression);
        }
        throw new IllegalArgumentException("Don't know how to treat " + expression + " as an expression.");
    }

    /**
     * Allows repositories to be added to this script.
     */
    public RepositoriesBuilder repositories() {
        return block.repositories;
    }

    /**
     * Allows dependencies to be added to this script.
     */
    public DependenciesBuilder dependencies() {
        return block.dependencies;
    }

    /**
     * Allows test suites to be added to this script.
     */
    public TestingBuilder testing() {
        return block.testing;
    }

    /**
     * Adds a top level method invocation statement.
     *
     * @return this
     */
    public BuildScriptBuilder methodInvocation(@Nullable String comment, String methodName, Object... methodArgs) {
        block.methodInvocation(comment, methodName, methodArgs);
        return this;
    }

    /**
     * Adds a top level method invocation statement.
     *
     * @return this
     */
    public BuildScriptBuilder methodInvocation(@Nullable String comment, Expression target, String methodName, Object... methodArgs) {
        block.methodInvocation(comment, target, methodName, methodArgs);
        return this;
    }

    /**
     * Adds a top level property assignment statement.
     *
     * @return this
     */
    public BuildScriptBuilder propertyAssignment(@Nullable String comment, String propertyName, Object propertyValue) {
        block.propertyAssignment(comment, propertyName, propertyValue, true);
        return this;
    }

    /**
     * Adds a top level block statement.
     *
     * @return The body of the block, to which further statements can be added.
     */
    public ScriptBlockBuilder block(@Nullable String comment, String methodName) {
        return block.block(comment, methodName);
    }

    /**
     * Adds a top level block statement.
     */
    public BuildScriptBuilder block(@Nullable String comment, String methodName, Action<? super ScriptBlockBuilder> blockContentBuilder) {
        blockContentBuilder.execute(block.block(comment, methodName));
        return this;
    }

    /**
     * Adds a method invocation statement to the configuration of a particular task.
     */
    public BuildScriptBuilder taskMethodInvocation(@Nullable String comment, String taskName, String taskType, String methodName, Object... methodArgs) {
        block.tasks.add(
            new TaskSelector(taskName, taskType),
            new MethodInvocation(comment, new MethodInvocationExpression(null, methodName, expressionValues(methodArgs))));
        return this;
    }

    /**
     * Adds a property assignment statement to the configuration of a particular task.
     */
    public BuildScriptBuilder taskPropertyAssignment(@Nullable String comment, String taskName, String taskType, String propertyName, Object propertyValue) {
        block.tasks.add(
            new TaskSelector(taskName, taskType),
            new PropertyAssignment(comment, propertyName, expressionValue(propertyValue), true));
        return this;
    }

    /**
     * Adds a property assignment statement to the configuration of all tasks of a particular type.
     */
    public BuildScriptBuilder taskPropertyAssignment(@Nullable String comment, String taskType, String propertyName, Object propertyValue) {
        block.taskTypes.add(
            new TaskTypeSelector(taskType),
            new PropertyAssignment(comment, propertyName, expressionValue(propertyValue), true));
        return this;
    }

    /**
     * Configure an existing task.
     *
     * @return An expression that can be used to refer to the task later.
     */
    public TaskConfiguration taskConfiguration(@Nullable String comment, String taskName, String taskType, Action<? super ScriptBlockBuilder> blockContentsBuilder) {
        TaskConfiguration conf = new TaskConfiguration(comment, taskName, taskType);
        block.add(conf);
        blockContentsBuilder.execute(conf.body);
        return conf;
    }

    /**
     * Configure an existing task within the given block.
     *
     * @return An expression that can be used to refer to the task later.
     */
    public TaskConfiguration taskConfiguration(@Nullable String comment, BlockStatement containingBlock, String taskName, String taskType, Action<? super ScriptBlockBuilder> blockContentsBuilder) {
        TaskConfiguration conf = new TaskConfiguration(comment, taskName, taskType);
        containingBlock.add(conf);
        blockContentsBuilder.execute(conf.body);
        return conf;
    }

    /**
     * Registers a task.
     *
     * @return An expression that can be used to refer to the task later.
     */
    public TaskRegistration taskRegistration(@Nullable String comment, String taskName, String taskType, Action<? super ScriptBlockBuilder> blockContentsBuilder) {
        TaskRegistration registration = new TaskRegistration(comment, taskName, taskType);
        block.add(registration);
        blockContentsBuilder.execute(registration.body);
        return registration;
    }

    /**
     * Registers a task within the containing block.
     *
     * @return An expression that can be used to refer to the task later.
     */
    public TaskRegistration taskRegistration(@Nullable String comment, BlockStatement containingBlock, String taskName, String taskType, Action<? super ScriptBlockBuilder> blockContentsBuilder) {
        TaskRegistration registration = new TaskRegistration(comment, taskName, taskType);
        containingBlock.add(registration);
        blockContentsBuilder.execute(registration.body);
        return registration;
    }

    /**
     * Configure an existing test suite within the given block.
     *
     * @return An expression that can be used to refer to the task later.
     */
    public SuiteConfiguration suiteConfiguration(@Nullable String comment, BlockStatement containingBlock, String taskName, String taskType, Action<? super ScriptBlockBuilder> blockContentsBuilder) {
        SuiteConfiguration conf = new SuiteConfiguration(comment, taskName, taskType);
        containingBlock.add(conf);
        blockContentsBuilder.execute(conf.body);
        return conf;
    }

    /**
     * Registers a test suite within the containing block.
     *
     * @return An expression that can be used to refer to the task later.
     */
    public SuiteRegistration suiteRegistration(@Nullable String comment, BlockStatement containingBlock, String taskName, String taskType, Action<? super ScriptBlockBuilder> blockContentsBuilder) {
        SuiteRegistration registration = new SuiteRegistration(comment, taskName, taskType);
        containingBlock.add(registration);
        blockContentsBuilder.execute(registration.body);
        return registration;
    }

    /**
     * Creates an element in the given container.
     *
     * @param varName A variable to use to reference the element, if required by the DSL. If {@code null}, then use the element name.
     *
     * @return An expression that can be used to refer to the element later in the script.
     */
    public Expression createContainerElement(@Nullable String comment, String container, String elementName, @Nullable String varName) {
        ContainerElement containerElement = new ContainerElement(comment, container, elementName, null, varName);
        block.add(containerElement);
        return containerElement;
    }

    public TemplateOperation create(Directory targetDirectory) {
        return () -> {
            if (useIncubatingAPIs) {
                headerLines.add(INCUBATING_APIS_WARNING);
            }

            File target = getTargetFile(targetDirectory);
            GFileUtils.mkdirs(target.getParentFile());
            try (PrintWriter writer = new PrintWriter(new FileWriter(target))) {
                PrettyPrinter printer = new PrettyPrinter(syntaxFor(dsl), writer, externalComments);
                printer.printFileHeader(headerLines);
                block.writeBodyTo(printer);
            } catch (Exception e) {
                throw new GradleException("Could not generate file " + target + ".", e);
            }
        };
    }

    public List<String> extractComments() {
        return block.extractComments();
    }

    private File getTargetFile(Directory targetDirectory) {
        return targetDirectory.file(dsl.fileNameFor(fileNameWithoutExtension)).getAsFile();
    }

    private static Syntax syntaxFor(BuildInitDsl dsl) {
        switch (dsl) {
            case KOTLIN:
                return new KotlinSyntax();
            case GROOVY:
                return new GroovySyntax();
            default:
                throw new IllegalStateException();
        }
    }

    public interface Expression {
    }

    private interface ExpressionValue extends Expression {
        boolean isBooleanType();

        String with(Syntax syntax);
    }

    private static class ChainedPropertyExpression implements ExpressionValue {
        private final ExpressionValue left;
        private final ExpressionValue right;

        public ChainedPropertyExpression(ExpressionValue left, ExpressionValue right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return left.with(syntax) + "." + right.with(syntax);
        }
    }

    private static class StringValue implements ExpressionValue {
        final CharSequence value;

        StringValue(CharSequence value) {
            this.value = value;
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return syntax.string(value.toString());
        }
    }

    private static class LiteralValue implements ExpressionValue {
        final Object literal;

        LiteralValue(Object literal) {
            this.literal = literal;
        }

        @Override
        public boolean isBooleanType() {
            return literal instanceof Boolean;
        }

        @Override
        public String with(Syntax syntax) {
            return literal.toString();
        }
    }

    private static class EnumValue implements ExpressionValue {
        final Enum<?> literal;

        EnumValue(Object literal) {
            this.literal = Cast.uncheckedNonnullCast(literal);
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return literal.getClass().getSimpleName() + "." + literal.name();
        }
    }

    private static class MapLiteralValue implements ExpressionValue {
        final Map<String, ExpressionValue> literal;

        public MapLiteralValue(Map<String, ExpressionValue> literal) {
            this.literal = literal;
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return syntax.mapLiteral(literal);
        }
    }

    /**
     * This class is part of an attempt to provide the minimal functionality needed to script calling methods
     * which have a no-arg closure as their only parameter.
     *
     * TODO: Improve this to be more general, handle more statements than just method calls,
     * indent multi-statement closures properly, possibly handle args
     */
    private static class NoArgClosureExpression implements ExpressionValue {
        final List<MethodInvocation> calls = new ArrayList<>();

        NoArgClosureExpression(MethodInvocation... calls) {
            this.calls.addAll(Arrays.asList(calls));
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return "{" + calls.stream()
                .map(call -> call.invocationExpression.with(syntax))
                .collect(Collectors.joining("\n", " ", " ")) +
                "}";
        }
    }

    private static class MethodInvocationExpression implements ExpressionValue {
        @Nullable
        private final ExpressionValue target;
        final String methodName;
        final List<ExpressionValue> arguments;

        MethodInvocationExpression(@Nullable ExpressionValue target, String methodName, List<ExpressionValue> arguments) {
            this.target = target;
            this.methodName = methodName;
            this.arguments = arguments;
        }

        MethodInvocationExpression(@Nullable ExpressionValue target, String methodName, NoArgClosureExpression closureArg) {
            this.target = target;
            this.methodName = methodName;
            this.arguments = Collections.singletonList(closureArg);
        }

        MethodInvocationExpression(String methodName) {
            this(null, methodName, Collections.emptyList());
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            StringBuilder result = new StringBuilder();
            if (target != null) {
                result.append(target.with(syntax));
                result.append('.');
            }
            result.append(methodName);

            boolean onlyArgIsClosure = arguments.size() == 1 && arguments.get(0) instanceof NoArgClosureExpression;

            if (onlyArgIsClosure) {
                result.append(' ');
            } else {
                result.append("(");
            }

            for (int i = 0; i < arguments.size(); i++) {
                ExpressionValue argument = arguments.get(i);
                if (i == 0) {
                    result.append(syntax.firstArg(argument));
                } else {
                    result.append(", ");
                    result.append(argument.with(syntax));
                }
            }

            if (onlyArgIsClosure) {
                result.append(' ');
            } else {
                result.append(")");
            }

            return result.toString();
        }
    }

    private static class ContainerElementExpression implements ExpressionValue {
        private final String container;
        private final String element;

        public ContainerElementExpression(String container, String element) {
            this.container = container;
            this.element = element;
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return syntax.containerElement(container, element);
        }
    }

    private static class PluginSpec extends AbstractStatement {
        final String id;
        @Nullable
        final String version;

        PluginSpec(String id, @Nullable String version, String comment) {
            super(comment);
            this.id = id;
            this.version = version;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.println(printer.syntax.pluginDependencySpec(id, version));
        }
    }

    private static class DepSpec extends AbstractStatement {
        final String configuration;
        final List<String> deps;

        DepSpec(String configuration, @Nullable String comment, List<String> deps) {
            super(comment);
            this.configuration = configuration;
            this.deps = deps;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            for (String dep : deps) {
                printer.println(printer.syntax.dependencySpec(configuration, printer.syntax.string(dep)));
            }
        }
    }

    private static class PlatformDepSpec extends AbstractStatement {
        private final String configuration;
        private final String dep;

        PlatformDepSpec(String configuration, @Nullable String comment, String dep) {
            super(comment);
            this.configuration = configuration;
            this.dep = dep;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.println(printer.syntax.dependencySpec(
                configuration, "platform(" + printer.syntax.string(dep) + ")"
            ));
        }
    }

    private static class SelfDepSpec extends AbstractStatement {
        private final String configuration;

        SelfDepSpec(String configuration, @Nullable String comment) {
            super(comment);
            this.configuration = configuration;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.println(printer.syntax.dependencySpec(configuration, "project"));
        }
    }

    private static class ProjectDepSpec extends AbstractStatement {
        private final String configuration;
        private final String projectPath;

        ProjectDepSpec(String configuration, String comment, String projectPath) {
            super(comment);
            this.configuration = configuration;
            this.projectPath = projectPath;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.println(printer.syntax.dependencySpec(configuration, "project(" + printer.syntax.string(projectPath) + ")"));
        }
    }

    private interface ConfigSelector {
        @Nullable
        String codeBlockSelectorFor(Syntax syntax);
    }

    private static class TaskSelector implements ConfigSelector {

        final String taskName;
        final String taskType;

        private TaskSelector(String taskName, String taskType) {
            this.taskName = taskName;
            this.taskType = taskType;
        }

        @Nullable
        @Override
        public String codeBlockSelectorFor(Syntax syntax) {
            return syntax.taskSelector(this);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TaskSelector that = (TaskSelector) o;
            return Objects.equal(taskName, that.taskName) && Objects.equal(taskType, that.taskType);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(taskName, taskType);
        }
    }

    private static class TaskTypeSelector implements ConfigSelector {

        final String taskType;

        TaskTypeSelector(String taskType) {
            this.taskType = taskType;
        }

        @Nullable
        @Override
        public String codeBlockSelectorFor(Syntax syntax) {
            return syntax.taskByTypeSelector(taskType);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TaskTypeSelector that = (TaskTypeSelector) o;
            return Objects.equal(taskType, that.taskType);
        }

        @Override
        public int hashCode() {
            return taskType.hashCode();
        }
    }

    private static class ConventionSelector implements ConfigSelector {

        final String conventionName;

        private ConventionSelector(String conventionName) {
            this.conventionName = conventionName;
        }

        @Override
        public String codeBlockSelectorFor(Syntax syntax) {
            return syntax.conventionSelector(this);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ConventionSelector that = (ConventionSelector) o;
            return Objects.equal(conventionName, that.conventionName);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(conventionName);
        }
    }

    /**
     * Represents a statement in a script. Each statement has an optional comment that explains its purpose.
     */
    public interface Statement {
        enum Type {Empty, Single, Group}

        @Nullable
        String getComment();

        /**
         * Returns details of the size of this statement. Returns {@link Type#Empty} when this statement is empty and should not be included in the script.
         */
        Type type();

        /**
         * Writes this statement to the given printer. Should not write the comment. Called only when {@link #type()} returns a value != {@link Type#Empty}
         */
        void writeCodeTo(PrettyPrinter printer);
    }

    private static abstract class AbstractStatement implements Statement {

        final String comment;

        AbstractStatement(@Nullable String comment) {
            this.comment = comment;
        }

        @Nullable
        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public Type type() {
            return Type.Single;
        }
    }

    private static class MethodInvocation extends AbstractStatement {

        final MethodInvocationExpression invocationExpression;

        private MethodInvocation(String comment, MethodInvocationExpression invocationExpression) {
            super(comment);
            this.invocationExpression = invocationExpression;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.println(invocationExpression.with(printer.syntax));
        }
    }

    private static class ContainerElement extends AbstractStatement implements ExpressionValue {

        private final String comment;
        private final String container;
        private final String elementName;
        @Nullable
        private final String varName;
        @Nullable
        private final String elementType;
        private final ScriptBlockImpl body = new ScriptBlockImpl();

        public ContainerElement(String comment, String container, String elementName, @Nullable String elementType, @Nullable String varName) {
            super(null);
            this.comment = comment;
            this.container = container;
            this.elementName = elementName;
            this.elementType = elementType;
            this.varName = varName;
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            Statement statement = printer.syntax.createContainerElement(comment, container, elementName, elementType, varName, body.statements);
            printer.printStatement(statement);
        }

        @Override
        public String with(Syntax syntax) {
            return syntax.referenceCreatedContainerElement(container, elementName, varName);
        }
    }

    private static class PropertyAssignment extends AbstractStatement {

        final String propertyName;
        final ExpressionValue propertyValue;
        final boolean legacyProperty;

        private PropertyAssignment(String comment, String propertyName, ExpressionValue propertyValue, boolean legacyProperty) {
            super(comment);
            this.propertyName = propertyName;
            this.propertyValue = propertyValue;
            this.legacyProperty = legacyProperty;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.println(printer.syntax.propertyAssignment(this));
        }
    }

    private static class SingleLineComment extends AbstractStatement {
        private SingleLineComment(String comment) {
            super(comment);
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            // NO OP
        }
    }

    /**
     * Represents the contents of a block.
     */
    private interface BlockBody {
        void writeBodyTo(PrettyPrinter printer);

        List<Statement> getStatements();
    }

    private static class BlockStatement implements Statement {
        private final String comment;
        final String blockSelector;
        final ScriptBlockImpl body = new ScriptBlockImpl();

        BlockStatement(String blockSelector) {
            this(null, blockSelector);
        }

        BlockStatement(@Nullable String comment, String blockSelector) {
            this.comment = comment;
            this.blockSelector = blockSelector;
        }

        @Nullable
        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public Type type() {
            return body.type();
        }

        void add(Statement statement) {
            body.add(statement);
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock(blockSelector, body);
        }
    }

    private static class ScriptBlock extends BlockStatement {
        ScriptBlock(String comment, String blockSelector) {
            super(comment, blockSelector);
        }

        @Override
        public Type type() {
            // Always treat as non-empty
            return Type.Group;
        }
    }

    private static class RepositoriesBlock extends BlockStatement implements RepositoriesBuilder {
        private final BuildScriptBuilder builder;

        RepositoriesBlock(final BuildScriptBuilder builder) {
            super("repositories");
            this.builder = builder;
        }

        @Override
        public void mavenLocal(String comment) {
            add(new MethodInvocation(comment, new MethodInvocationExpression("mavenLocal")));
        }

        @Override
        public void mavenCentral(@Nullable String comment) {
            add(new MethodInvocation(comment, new MethodInvocationExpression("mavenCentral")));
        }

        @Override
        public void gradlePluginPortal(@Nullable String comment) {
            add(new MethodInvocation(comment, new MethodInvocationExpression("gradlePluginPortal")));
        }

        @Override
        public void maven(String comment, String url) {
            add(new MavenRepoExpression(comment, url, builder));
        }
    }

    private static class DependenciesBlock implements DependenciesBuilder, Statement, BlockBody {
        final ListMultimap<String, Statement> dependencies = MultimapBuilder.linkedHashKeys().arrayListValues().build();
        final ListMultimap<String, Statement> constraints = MultimapBuilder.linkedHashKeys().arrayListValues().build();

        @Override
        public void dependency(String configuration, @Nullable String comment, String... dependencies) {
            this.dependencies.put(configuration, new DepSpec(configuration, comment, Arrays.asList(dependencies)));
        }

        @Override
        public void dependencyConstraint(String configuration, @Nullable String comment, String... constraints) {
            this.constraints.put(configuration, new DepSpec(configuration, comment, Arrays.asList(constraints)));
        }

        @Override
        public void platformDependency(String configuration, @Nullable String comment, String dependency) {
            this.dependencies.put(configuration, new PlatformDepSpec(configuration, comment, dependency));
        }

        @Override
        public void projectDependency(String configuration, @Nullable String comment, String projectPath) {
            this.dependencies.put(configuration, new ProjectDepSpec(configuration, comment, projectPath));
        }

        @Override
        public void selfDependency(String configuration, @Nullable String comment) {
            this.dependencies.put(configuration, new SelfDepSpec(configuration, comment));
        }

        @Nullable
        @Override
        public String getComment() {
            return null;
        }

        @Override
        public Type type() {
            return dependencies.isEmpty() && constraints.isEmpty() ? Type.Empty : Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock("dependencies", this);
        }

        @Override
        public void writeBodyTo(PrettyPrinter printer) {
            if (!this.constraints.isEmpty()) {
                ScriptBlockImpl constraintsBlock = new ScriptBlockImpl();
                for (String config : this.constraints.keySet()) {
                    for (Statement constraintSpec : this.constraints.get(config)) {
                            constraintsBlock.add(constraintSpec);
                    }
                }
                printer.printBlock("constraints", constraintsBlock);
            }

            for (String config : dependencies.keySet()) {
                for (Statement depSpec : dependencies.get(config)) {
                    printer.printStatement(depSpec);
                }
            }
        }

        @Override
        public List<Statement> getStatements() {
            List<Statement> statements = new ArrayList<>();
            if (!constraints.isEmpty()) {
                ScriptBlock constraintsBlock = new ScriptBlock(null, "constraints");
                for (String config : constraints.keySet()) {
                    for(Statement statement : constraints.get(config)) {
                        constraintsBlock.add(statement);
                    }
                }
                statements.add(constraintsBlock);
            }

            for (String config : dependencies.keySet()) {
                statements.addAll(dependencies.get(config));
            }
            return statements;
        }
    }

    private static class TestingBlock extends BlockStatement implements TestingBuilder, BlockBody {
        private final BuildScriptBuilder builder;
        private final List<SuiteSpec> suites = new ArrayList<>();

        TestingBlock(BuildScriptBuilder builder) {
            super("testing");
            this.builder = builder;
        }

        @Override
        public Type type() {
            return Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock(blockSelector, this);
        }


        @Override
        public void writeBodyTo(PrettyPrinter printer) {
            if (!suites.isEmpty()) {
                ScriptBlockImpl suitesBlock = new ScriptBlockImpl();
                for (Statement suite : suites) {
                    suitesBlock.add(suite);
                }
                printer.printBlock("suites", suitesBlock);
            }
        }

        @Override
        public List<Statement> getStatements() {
            return new ArrayList<>(suites);
        }

        @Override
        public void junitSuite(String name) {
            suites.add(new SuiteSpec(null, name, SuiteSpec.TestSuiteFramework.JUNIT, builder));
        }

        @Override
        public void junitPlatformSuite(String name) {
            suites.add(new SuiteSpec(null, name, SuiteSpec.TestSuiteFramework.JUNIT_PLATFORM, builder));
        }
    }

    private static class SuiteSpec extends AbstractStatement {
        private final BuildScriptBuilder builder;

        private final String name;
        private final TestSuiteFramework framework;

        private final DependenciesBlock dependencies = new DependenciesBlock();
        private final TargetsBlock targets;

        private final boolean isDefaultTestSuite;
        private final boolean isDefaultFramework;

        SuiteSpec(@Nullable String comment, String name, TestSuiteFramework framework, BuildScriptBuilder builder) {
            super(comment);
            this.builder = builder;
            this.framework = framework;
            this.name = name;
            targets = new TargetsBlock(builder);

            isDefaultTestSuite = JvmTestSuitePlugin.DEFAULT_TEST_SUITE_NAME.equals(name);
            isDefaultFramework = framework == TestSuiteFramework.getDefault();

            if (isDefaultTestSuite) {
                dependencies.dependency("implementation", null, framework.implementationDependency);
            } else {
                dependencies.selfDependency("implementation", null);
                targets.all(true);
            }
        }

        private Action<? super ScriptBlockBuilder> buildSuiteConfigurationContents() {
            return b -> {
                if (isDefaultTestSuite || !isDefaultFramework) {
                    b.methodInvocation(null, framework.method.methodName);
                }

                if (!dependencies.dependencies.isEmpty()) {
                    b.statement(null, dependencies);
                }

                if (!targets.targets.isEmpty()) {
                    b.statement(null, targets);
                }
            };
        }

        public String getName() {
            return name;
        }

        public boolean isDefaultTestSuite() {
            return isDefaultTestSuite;
        }

        @Override
        public Type type() {
            return Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            if (isDefaultTestSuite) {
                printer.printStatement(builder.suiteConfiguration(null, builder.block.testing, name, JvmTestSuite.class.getSimpleName(), buildSuiteConfigurationContents()));
            } else {
                printer.printStatement(builder.suiteRegistration(null, builder.block.testing, name, JvmTestSuite.class.getSimpleName(), buildSuiteConfigurationContents()));
            }
        }

        public enum TestSuiteFramework {
            JUNIT(new MethodInvocationExpression("useJUnit"), "junit:junit:4.13"),
            JUNIT_PLATFORM(new MethodInvocationExpression("useJUnitPlatform"), "org.junit.jupiter:junit-jupiter:5.7.2");

            final MethodInvocationExpression method;
            final String implementationDependency;

            TestSuiteFramework(MethodInvocationExpression method, String implementationDependency) {
                this.method = method;
                this.implementationDependency = implementationDependency;
            }

            public static TestSuiteFramework getDefault() {
                return JUNIT_PLATFORM;
            }
        }
    }

    private static class TargetsBlock extends BlockStatement implements TargetsBuilder, BlockBody {
        private final BuildScriptBuilder builder;
        private final List<TargetSpec> targets = new ArrayList<>();

        TargetsBlock(BuildScriptBuilder builder) {
            super("targets");
            this.builder = builder;
        }

        @Override
        public Type type() {
            return Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock(blockSelector, this);
        }

        @Override
        public void writeBodyTo(PrettyPrinter printer) {
            if (!targets.isEmpty()) {
                for (Statement target : targets) {
                    printer.printStatement(target);
                }
            }
        }

        @Override
        public List<Statement> getStatements() {
            return new ArrayList<>(targets);
        }

        @Override
        public void all(boolean testTaskShouldRunAfter) {
            targets.add(new TargetSpec(null, "all", builder, testTaskShouldRunAfter));
        }
    }

    private static class TargetSpec extends BlockStatement implements BlockBody {
        private final BuildScriptBuilder builder;
        private final String name;
        private final boolean testTaskShouldRunAfter;

        TargetSpec(@Nullable String comment, String name, BuildScriptBuilder builder, boolean testTaskShouldRunAfter) {
            super(comment);
            this.builder = builder;
            this.name = name;
            this.testTaskShouldRunAfter = testTaskShouldRunAfter;

            if (testTaskShouldRunAfter) {
                configureShouldRunAfterTest();
            }
        }

        @Override
        public Type type() {
            return Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock(name, this);
        }

        private void configureShouldRunAfterTest() {
            final MethodInvocation shouldRunAfterCall = new MethodInvocation(null, new MethodInvocationExpression(null, "shouldRunAfter", Collections.singletonList(new LiteralValue("test"))));
            final NoArgClosureExpression configBlock = new NoArgClosureExpression(shouldRunAfterCall);
            final MethodInvocation functionalTestConfiguration = new MethodInvocation(null, new MethodInvocationExpression(expressionValue(builder.propertyExpression("testTask")), "configure", configBlock));
            add(functionalTestConfiguration);
        }

        @Override
        public void writeBodyTo(PrettyPrinter printer) {
            for (Statement statement : body.statements) {
                printer.printStatement(statement);
            }
        }

        @Override
        public List<Statement> getStatements() {
            return body.statements;
        }
    }

    private static class MavenRepoExpression extends AbstractStatement {
        private final URI uri;
        private final BuildScriptBuilder builder;

        MavenRepoExpression(@Nullable String comment, String url, BuildScriptBuilder builder) {
            super(comment);
            this.uri = uriFromString(url);
            this.builder = builder;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            builder.mavenRepoURLHandler.handleURL(uri, printer);
        }
    }

    public static class ScriptBlockImpl implements ScriptBlockBuilder, BlockBody {
        final List<Statement> statements = new ArrayList<>();

        public void add(Statement statement) {
            statements.add(statement);
        }

        @Override
        public List<Statement> getStatements() {
            return statements;
        }

        public Statement.Type type() {
            for (Statement statement : statements) {
                if (statement.type() != Statement.Type.Empty) {
                    return Statement.Type.Group;
                }
            }
            return Statement.Type.Empty;
        }

        @Override
        public void writeBodyTo(PrettyPrinter printer) {
            printer.printStatements(statements);
        }

        @Override
        public void propertyAssignment(String comment, String propertyName, Object propertyValue, boolean legacyProperty) {
            statements.add(new PropertyAssignment(comment, propertyName, expressionValue(propertyValue), legacyProperty));
        }

        @Override
        public void methodInvocation(String comment, String methodName, Object... methodArgs) {
            statements.add(new MethodInvocation(comment, new MethodInvocationExpression(null, methodName, expressionValues(methodArgs))));
        }

        @Override
        public void methodInvocation(@Nullable String comment, Expression target, String methodName, Object... methodArgs) {
            statements.add(new MethodInvocation(comment, new MethodInvocationExpression(expressionValue(target), methodName, expressionValues(methodArgs))));
        }

        @Override
        public ScriptBlockBuilder block(String comment, String methodName) {
            ScriptBlock scriptBlock = new ScriptBlock(comment, methodName);
            statements.add(scriptBlock);
            return scriptBlock.body;
        }

        @Override
        public void statement(@Nullable String comment, Statement statement) {
            statements.add(statement);
        }

        @Override
        public void block(@Nullable String comment, String methodName, Action<? super ScriptBlockBuilder> blockContentsBuilder) {
            blockContentsBuilder.execute(block(comment, methodName));
        }

        @Override
        public Expression containerElement(@Nullable String comment, String container, String elementName, @Nullable String elementType, Action<? super ScriptBlockBuilder> blockContentsBuilder) {
            ContainerElement containerElement = new ContainerElement(comment, container, elementName, elementType, null);
            statements.add(containerElement);
            blockContentsBuilder.execute(containerElement.body);
            return containerElement;
        }

        @Override
        public Expression propertyExpression(String value) {
            return new LiteralValue(value);
        }

        @Override
        public void comment(String comment) {
            statements.add(new SingleLineComment(comment));
        }
    }

    private static class TopLevelBlock extends ScriptBlockImpl {
        final BlockStatement plugins = new BlockStatement("plugins");
        final RepositoriesBlock repositories;
        final DependenciesBlock dependencies = new DependenciesBlock();
        final TestingBlock testing;
        final ConfigurationStatements<TaskTypeSelector> taskTypes = new ConfigurationStatements<>();
        final ConfigurationStatements<TaskSelector> tasks = new ConfigurationStatements<>();
        final ConfigurationStatements<ConventionSelector> conventions = new ConfigurationStatements<>();
        final BuildScriptBuilder builder;

        private TopLevelBlock(BuildScriptBuilder builder) {
            repositories = new RepositoriesBlock(builder);
            testing = new TestingBlock(builder);
            this.builder = builder;
        }

        @Override
        public void writeBodyTo(PrettyPrinter printer) {
            printer.printStatement(plugins);
            printer.printStatement(repositories);
            printer.printStatement(dependencies);
            if (builder.useTestSuites && !builder.getSuites().isEmpty()) {
                printer.printStatement(testing);
            }
            super.writeBodyTo(printer);
            printer.printStatement(conventions);
            printer.printStatement(taskTypes);
            for (SuiteSpec suite : testing.suites) {
                if (!suite.isDefaultTestSuite()) {
                    addCheckDependsOn(suite);
                }
            }
            printer.printStatement(tasks);
        }

        private void addCheckDependsOn(SuiteSpec suite) {
            final ExpressionValue testSuites = expressionValue(builder.propertyExpression(builder.propertyExpression("testing"), "suites"));
            if (builder.dsl == BuildInitDsl.GROOVY) {
                final Expression suiteDependedUpon = builder.propertyExpression(testSuites, suite.getName());
                builder.taskMethodInvocation(null, "check", Task.class.getSimpleName(), "dependsOn", suiteDependedUpon);
            } else {
                final ExpressionValue namedMethod = new MethodInvocationExpression(testSuites, "named", Collections.singletonList(new StringValue(suite.getName())));
                builder.taskMethodInvocation(null, "check", Task.class.getSimpleName(), "dependsOn", namedMethod);
            }
        }

        public List<String> extractComments() {
            List<String> comments = new ArrayList<>();
            collectComments(plugins.body, comments);
            collectComments(repositories.body, comments);
            collectComments(dependencies, comments);
            for (Statement otherBlock : getStatements()) {
                if (otherBlock instanceof BlockStatement) {
                    collectComments(((BlockStatement) otherBlock).body, comments);
                }
            }
            return comments;
        }

        private void collectComments(BlockBody body, List<String> comments) {
            for (Statement statement : body.getStatements()) {
                if (statement.getComment() != null) {
                    comments.add(statement.getComment());
                }
            }
        }
    }

    private static class TaskConfiguration implements Statement, ExpressionValue {
        final String taskName;
        final String taskType;
        final String comment;
        final ScriptBlockImpl body = new ScriptBlockImpl();

        TaskConfiguration(@Nullable String comment, String taskName, String taskType) {
            this.comment = comment;
            this.taskName = taskName;
            this.taskType = taskType;
        }

        @Nullable
        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public Type type() {
            return Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock(printer.syntax.taskConfiguration(taskName, taskType), body);
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return syntax.referenceTask(taskName);
        }
    }

    private static class TaskRegistration implements Statement, ExpressionValue {
        final String taskName;
        final String taskType;
        final String comment;
        final ScriptBlockImpl body = new ScriptBlockImpl();

        TaskRegistration(@Nullable String comment, String taskName, String taskType) {
            this.comment = comment;
            this.taskName = taskName;
            this.taskType = taskType;
        }

        @Nullable
        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public Type type() {
            return Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock(printer.syntax.taskRegistration(taskName, taskType), body);
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return syntax.referenceTask(taskName);
        }
    }

    private static class SuiteConfiguration implements Statement, ExpressionValue {
        final String suiteName;
        final String suiteType;
        final String comment;
        final ScriptBlockImpl body = new ScriptBlockImpl();

        SuiteConfiguration(@Nullable String comment, String suiteName, String suiteType) {
            this.comment = comment;
            this.suiteName = suiteName;
            this.suiteType = suiteType;
        }

        @Nullable
        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public Type type() {
            return Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock(printer.syntax.suiteConfiguration(suiteName, suiteType), body);
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return syntax.referenceSuite(suiteName);
        }
    }

    private static class SuiteRegistration implements Statement, ExpressionValue {
        final String suiteName;
        final String suiteType;
        final String comment;
        final ScriptBlockImpl body = new ScriptBlockImpl();

        SuiteRegistration(@Nullable String comment, String suiteName, String suiteType) {
            this.comment = comment;
            this.suiteName = suiteName;
            this.suiteType = suiteType;
        }

        @Nullable
        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public Type type() {
            return Type.Group;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            printer.printBlock(printer.syntax.suiteRegistration(suiteName, suiteType), body);
        }

        @Override
        public boolean isBooleanType() {
            return false;
        }

        @Override
        public String with(Syntax syntax) {
            return syntax.referenceSuite(suiteName);
        }
    }

    private static class ConfigurationStatements<T extends ConfigSelector> implements Statement {
        final ListMultimap<T, Statement> blocks = MultimapBuilder.linkedHashKeys().arrayListValues().build();

        void add(T selector, Statement statement) {
            blocks.put(selector, statement);
        }

        @Nullable
        @Override
        public String getComment() {
            return null;
        }

        @Override
        public Type type() {
            return blocks.isEmpty() ? Type.Empty : Type.Single;
        }

        @Override
        public void writeCodeTo(PrettyPrinter printer) {
            for (T configSelector : blocks.keySet()) {
                String selector = configSelector.codeBlockSelectorFor(printer.syntax);
                if (selector != null) {
                    BlockStatement statement = new BlockStatement(selector);
                    statement.body.statements.addAll(blocks.get(configSelector));
                    printer.printStatement(statement);
                } else {
                    printer.printStatements(blocks.get(configSelector));
                }
            }
        }
    }

    private static final class PrettyPrinter {

        private final Syntax syntax;
        private final PrintWriter writer;
        private final boolean externalComments;
        private String indent = "";
        private String eolComment = null;
        private int commentCount = 0;
        private boolean needSeparatorLine = true;
        private boolean firstStatementOfBlock = false;
        private boolean hasSeparatorLine = false;

        PrettyPrinter(Syntax syntax, PrintWriter writer, boolean externalComments) {
            this.syntax = syntax;
            this.writer = writer;
            this.externalComments = externalComments;
        }

        public void printFileHeader(Collection<String> lines) {
            if (externalComments) {
                return;
            }
            println("/*");
            println(" * This file was generated by the Gradle 'init' task.");
            if (!lines.isEmpty()) {
                println(" *");
                for (String headerLine : lines) {
                    if (headerLine.isEmpty()) {
                        println(" *");
                    } else {
                        println(" * " + headerLine);
                    }
                }
            }
            println(" */");
        }

        public void printBlock(String blockSelector, BlockBody blockBody) {
            String indentBefore = indent;

            println(blockSelector + " {");
            indent = indent + "    ";
            needSeparatorLine = false;
            firstStatementOfBlock = true;

            blockBody.writeBodyTo(this);

            indent = indentBefore;
            println("}");

            // Write a line separator after any block
            needSeparatorLine = true;
        }

        public void printStatements(List<? extends Statement> statements) {
            for (Statement statement : statements) {
                printStatement(statement);
            }
        }

        private void printStatementSeparator() {
            if (needSeparatorLine && !hasSeparatorLine) {
                println();
                needSeparatorLine = false;
            }
        }

        private void printStatement(Statement statement) {
            Statement.Type type = statement.type();
            if (type == Statement.Type.Empty) {
                return;
            }

            boolean hasComment = statement.getComment() != null;

            // Add separators before and after anything with a comment or that is a block or group of statements
            boolean needsSeparator = hasComment || type == Statement.Type.Group;
            if (needsSeparator && !firstStatementOfBlock) {
                needSeparatorLine = true;
            }

            printStatementSeparator();

            if (hasComment) {
                if (externalComments) {
                    commentCount++;
                    eolComment = " // <" + commentCount + ">";
                } else {
                    for (String line : splitComment(statement.getComment())) {
                        println("// " + line);
                    }
                }
            }

            statement.writeCodeTo(this);

            firstStatementOfBlock = false;
            if (needsSeparator) {
                needSeparatorLine = true;
            }
        }

        private void println(String s) {
            if (!indent.isEmpty()) {
                writer.print(indent);
            }
            if (eolComment != null) {
                writer.println(s + eolComment);
                eolComment = null;
            } else {
                writer.println(s);
            }
            hasSeparatorLine = false;
        }

        private void println() {
            writer.println();
            hasSeparatorLine = true;
        }
    }

    private interface Syntax {

        String pluginDependencySpec(String pluginId, @Nullable String version);

        @SuppressWarnings("unused")
        String nestedPluginDependencySpec(String pluginId, @Nullable String version);

        String dependencySpec(String config, String notation);

        String propertyAssignment(PropertyAssignment expression);

        @Nullable
        String conventionSelector(ConventionSelector selector);

        String taskSelector(TaskSelector selector);

        String taskByTypeSelector(String taskType);

        String string(String string);

        String taskRegistration(String taskName, String taskType);

        String taskConfiguration(String taskName, String taskType);

        String suiteRegistration(String taskName, String taskType);

        String suiteConfiguration(String taskName, String taskType);

        String referenceTask(String taskName);

        String referenceSuite(String taskName);

        String mapLiteral(Map<String, ExpressionValue> map);

        String firstArg(ExpressionValue argument);

        Statement createContainerElement(@Nullable String comment, String container, String elementName, @Nullable String elementType, @Nullable String varName, List<Statement> body);

        String referenceCreatedContainerElement(String container, String elementName, @Nullable String varName);

        String containerElement(String container, String element);

        void configureConventionPlugin(@Nullable String comment, BlockStatement plugins, RepositoriesBlock repositories);
    }

    private static final class KotlinSyntax implements Syntax {
        @Override
        public String string(String string) {
            return '"' + string + '"';
        }

        @Override
        public String mapLiteral(Map<String, ExpressionValue> map) {
            StringBuilder builder = new StringBuilder();
            builder.append("mapOf(");
            boolean first = true;
            for (Map.Entry<String, ExpressionValue> entry : map.entrySet()) {
                if (first) {
                    first = false;
                } else {
                    builder.append(", ");
                }
                builder.append(string(entry.getKey()));
                builder.append(" to ");
                builder.append(entry.getValue().with(this));
            }
            builder.append(")");
            return builder.toString();
        }

        @Override
        public String firstArg(ExpressionValue argument) {
            return argument.with(this);
        }

        @Override
        public String pluginDependencySpec(String pluginId, @Nullable String version) {
            if (version != null) {
                return "id(\"" + pluginId + "\") version \"" + version + "\"";
            } else if (pluginId.contains(".")) {
                return "id(\"" + pluginId + "\")";
            }
            return pluginId.matches("[a-z]+") ? pluginId : "`" + pluginId + "`";
        }

        @Override
        public String nestedPluginDependencySpec(String pluginId, @Nullable String version) {
            if (version != null) {
                throw new UnsupportedOperationException();
            }
            return "plugins.apply(\"" + pluginId + "\")";
        }

        @Override
        public String dependencySpec(String config, String notation) {
            return config + "(" + notation + ")";
        }


        @Override
        public String propertyAssignment(PropertyAssignment expression) {
            String propertyName = expression.propertyName;
            ExpressionValue propertyValue = expression.propertyValue;
            if (expression.legacyProperty) {
                if (propertyValue.isBooleanType()) {
                    return booleanPropertyNameFor(propertyName) + " = " + propertyValue.with(this);
                }
                return propertyName + " = " + propertyValue.with(this);
            } else {
                return propertyName + ".set(" + propertyValue.with(this) + ")";
            }
        }

        // In Kotlin:
        //
        // > Boolean accessor methods (where the name of the getter starts with is and the name of
        // > the setter starts with set) are represented as properties which have the same name as
        // > the getter method. Boolean properties are visible with a `is` prefix in Kotlin
        //
        // https://kotlinlang.org/docs/reference/java-interop.html#getters-and-setters
        //
        // This code assumes all configurable Boolean property getters follow the `is` prefix convention.
        //
        private String booleanPropertyNameFor(String propertyName) {
            return "is" + StringUtils.capitalize(propertyName);
        }

        @Override
        public String conventionSelector(ConventionSelector selector) {
            return selector.conventionName;
        }

        @Override
        public String taskSelector(TaskSelector selector) {
            return "tasks.named<" + selector.taskType + ">(\"" + selector.taskName + "\")";
        }

        @Override
        public String taskByTypeSelector(String taskType) {
            return "tasks.withType<" + taskType + ">()";
        }

        @Override
        public String taskRegistration(String taskName, String taskType) {
            return "val " + taskName + " by tasks.registering(" + taskType + "::class)";
        }

        @Override
        public String taskConfiguration(String taskName, String taskType) {
            return "val " + taskName + " by tasks.getting(" + taskType + "::class)";
        }

        @Override
        public String suiteRegistration(String suiteName, String suiteType) {
            return "val " + suiteName + " by registering(" + suiteType + "::class)";
        }

        @Override
        public String suiteConfiguration(String suiteName, String suiteType) {
            return "val " + suiteName + " by getting(" + suiteType + "::class)";
        }

        @Override
        public String referenceTask(String taskName) {
            return taskName;
        }

        @Override
        public String referenceSuite(String suiteName) {
            return suiteName;
        }

        @Override
        public Statement createContainerElement(String comment, String container, String elementName, @Nullable String elementType, String varName, List<Statement> body) {
            String literal;
            if (varName == null) {
                if (elementType == null) {
                    literal = "val " + elementName + " by " + container + ".creating";
                } else {
                    literal = container + ".create<" + elementType + ">(" + string(elementName) + ")";
                }
            } else {
                if (elementType == null) {
                    literal = "val " + varName + " = " + container + ".create(" + string(elementName) + ")";
                } else {
                    literal = "val " + varName + " = " + container + ".create<" + elementType + ">(" + string(elementName) + ")";
                }
            }
            BlockStatement blockStatement = new ScriptBlock(comment, literal);
            for (Statement statement : body) {
                blockStatement.add(statement);
            }
            return blockStatement;
        }

        @Override
        public String referenceCreatedContainerElement(String container, String elementName, String varName) {
            if (varName == null) {
                return elementName;
            } else {
                return varName;
            }
        }

        @Override
        public String containerElement(String container, String element) {
            return container + "[" + string(element) + "]";
        }

        @Override
        public void configureConventionPlugin(@Nullable String comment, BlockStatement plugins, RepositoriesBlock repositories) {
            plugins.add(new PluginSpec("kotlin-dsl", null, comment));
        }
    }

    private static final class GroovySyntax implements Syntax {
        @Override
        public String string(String string) {
            return "'" + string + "'";
        }

        @Override
        public String mapLiteral(Map<String, ExpressionValue> map) {
            StringBuilder builder = new StringBuilder();
            builder.append("[");
            addEntries(map, builder);
            builder.append("]");
            return builder.toString();
        }

        private void addEntries(Map<String, ExpressionValue> map, StringBuilder builder) {
            boolean first = true;
            for (Map.Entry<String, ExpressionValue> entry : map.entrySet()) {
                if (first) {
                    first = false;
                } else {
                    builder.append(", ");
                }
                builder.append(entry.getKey());
                builder.append(": ");
                builder.append(entry.getValue().with(this));
            }
        }

        @Override
        public String firstArg(ExpressionValue argument) {
            if (argument instanceof MapLiteralValue) {
                MapLiteralValue literalValue = (MapLiteralValue) argument;
                StringBuilder builder = new StringBuilder();
                addEntries(literalValue.literal, builder);
                return builder.toString();
            } else {
                return argument.with(this);
            }
        }

        @Override
        public String pluginDependencySpec(String pluginId, @Nullable String version) {
            if (version != null) {
                return "id '" + pluginId + "' version '" + version + "'";
            }
            return "id '" + pluginId + "'";
        }

        @Override
        public String nestedPluginDependencySpec(String pluginId, @Nullable String version) {
            if (version != null) {
                throw new UnsupportedOperationException();
            }
            return "apply plugin: '" + pluginId + "'";
        }

        @Override
        public String dependencySpec(String config, String notation) {
            return config + " " + notation;
        }

        @Override
        public String propertyAssignment(PropertyAssignment expression) {
            String propertyName = expression.propertyName;
            ExpressionValue propertyValue = expression.propertyValue;
            return propertyName + " = " + propertyValue.with(this);
        }

        @Override
        public String conventionSelector(ConventionSelector selector) {
            return null;
        }

        @Override
        public String taskSelector(TaskSelector selector) {
            return "tasks.named('" + selector.taskName + "')";
        }

        @Override
        public String taskByTypeSelector(String taskType) {
            return "tasks.withType(" + taskType + ")";
        }

        @Override
        public String taskRegistration(String taskName, String taskType) {
            return "tasks.register('" + taskName + "', " + taskType + ")";
        }

        @Override
        public String taskConfiguration(String taskName, String taskType) {
            return taskName;
        }

        @Override
        public String suiteRegistration(String suiteName, String suiteType) {
            return suiteName;
        }

        @Override
        public String suiteConfiguration(String suiteName, String suiteType) {
            return suiteName;
        }

        @Override
        public String referenceTask(String taskName) {
            return "tasks." + taskName;
        }

        @Override
        public String referenceSuite(String suiteName) {
            return suiteName;
        }

        @Override
        public Statement createContainerElement(String comment, String container, String elementName, @Nullable String elementType, String varName, List<Statement> body) {
            ScriptBlock outerBlock = new ScriptBlock(comment, container);
            ScriptBlock innerBlock = new ScriptBlock(null, elementType == null ? elementName : elementName + "(" + elementType + ")");
            outerBlock.add(innerBlock);
            for (Statement statement : body) {
                innerBlock.add(statement);
            }
            return outerBlock;
        }

        @Override
        public String referenceCreatedContainerElement(String container, String elementName, String varName) {
            return container + "." + elementName;
        }

        @Override
        public String containerElement(String container, String element) {
            return container + "." + element;
        }

        @Override
        public void configureConventionPlugin(@Nullable String comment, BlockStatement plugins, RepositoriesBlock repositories) {
            plugins.add(new PluginSpec("groovy-gradle-plugin", null, comment));
        }
    }

    private interface MavenRepositoryURLHandler {
        void handleURL(URI repoLocation, PrettyPrinter printer);

        static MavenRepositoryURLHandler forInsecureProtocolOption(InsecureProtocolOption insecureProtocolOption, BuildInitDsl dsl, DocumentationRegistry documentationRegistry) {
            switch (insecureProtocolOption) {
                case FAIL:
                    return new FailingHandler(documentationRegistry);
                case WARN:
                    return new WarningHandler(dsl, documentationRegistry);
                case ALLOW:
                    return new AllowingHandler();
                case UPGRADE:
                    return new UpgradingHandler();
                default:
                    throw new IllegalStateException(String.format("Unknown handler: '%s'.", insecureProtocolOption));
            }
        }

        abstract class AbstractMavenRepositoryURLHandler implements MavenRepositoryURLHandler {
            @Override
            public void handleURL(URI repoLocation, PrettyPrinter printer) {
                ScriptBlockImpl statements = new ScriptBlockImpl();

                if (GUtil.isSecureUrl(repoLocation)) {
                    handleSecureURL(repoLocation, statements);
                } else {
                    handleInsecureURL(repoLocation, statements);
                }

                printer.printBlock("maven", statements);
            }

            protected void handleSecureURL(URI repoLocation, BuildScriptBuilder.ScriptBlockImpl statements) {
                statements.propertyAssignment(null, "url", new MethodInvocationExpression(null, "uri", Collections.singletonList(new StringValue(repoLocation.toString()))), true);
            }

            protected abstract void handleInsecureURL(URI repoLocation, BuildScriptBuilder.ScriptBlockImpl statements);
        }

        class FailingHandler extends AbstractMavenRepositoryURLHandler {
            private final DocumentationRegistry documentationRegistry;

            public FailingHandler(DocumentationRegistry documentationRegistry) {
                this.documentationRegistry = documentationRegistry;
            }

            @Override
            protected void handleInsecureURL(URI repoLocation, ScriptBlockImpl statements) {
                LOGGER.error("Gradle found an insecure protocol in a repository definition. The current strategy for handling insecure URLs is to fail. For more options, see {}.", documentationRegistry.getDocumentationFor("build_init_plugin", "allow_insecure"));
                throw new GradleException(String.format("Build generation aborted due to insecure protocol in repository: %s", repoLocation));
            }
        }

        class WarningHandler extends AbstractMavenRepositoryURLHandler {
            private final BuildInitDsl dsl;
            private final DocumentationRegistry documentationRegistry;

            public WarningHandler(BuildInitDsl dsl, DocumentationRegistry documentationRegistry) {
                this.dsl = dsl;
                this.documentationRegistry = documentationRegistry;
            }

            @Override
            protected void handleInsecureURL(URI repoLocation, BuildScriptBuilder.ScriptBlockImpl statements) {
                LOGGER.warn("Gradle found an insecure protocol in a repository definition. You will have to opt into allowing insecure protocols in the generated build file. See {}.", documentationRegistry.getDocumentationFor("build_init_plugin", "allow_insecure"));
                // use the insecure URL as-is
                statements.propertyAssignment(null, "url", new BuildScriptBuilder.MethodInvocationExpression(null, "uri", Collections.singletonList(new BuildScriptBuilder.StringValue(repoLocation.toString()))), true);
                // Leave a commented out block for opting into using the insecure repository
                statements.comment(buildAllowInsecureProtocolComment(dsl));
            }

            private String buildAllowInsecureProtocolComment(BuildInitDsl dsl) {
                final PropertyAssignment assignment = new PropertyAssignment(null, "allowInsecureProtocol", new BuildScriptBuilder.LiteralValue(true), true);

                final StringWriter result = new StringWriter();
                try (PrintWriter writer = new PrintWriter(result)) {
                    PrettyPrinter printer = new PrettyPrinter(syntaxFor(dsl), writer, false);
                    assignment.writeCodeTo(printer);
                    return result.toString();
                } catch (Exception e) {
                    throw new GradleException("Could not write comment.", e);
                }
            }
        }

        class UpgradingHandler extends AbstractMavenRepositoryURLHandler {
            @Override
            protected void handleInsecureURL(URI repoLocation, BuildScriptBuilder.ScriptBlockImpl statements) {
                // convert the insecure url for this repository from http to https
                final URI secureUri = GUtil.toSecureUrl(repoLocation);
                statements.propertyAssignment(null, "url", new BuildScriptBuilder.MethodInvocationExpression(null, "uri", Collections.singletonList(new BuildScriptBuilder.StringValue(secureUri.toString()))), true);
            }
        }

        class AllowingHandler extends AbstractMavenRepositoryURLHandler {
            @Override
            protected void handleInsecureURL(URI repoLocation, BuildScriptBuilder.ScriptBlockImpl statements) {
                // use the insecure URL as-is
                statements.propertyAssignment(null, "url", new BuildScriptBuilder.MethodInvocationExpression(null, "uri", Collections.singletonList(new BuildScriptBuilder.StringValue(repoLocation.toString()))), true);
                // Opt into using an insecure protocol with this repository
                statements.propertyAssignment(null, "allowInsecureProtocol", new BuildScriptBuilder.LiteralValue(true), true);
            }
        }
    }
}
