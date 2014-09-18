/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.api.internal;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.RuleAction;

public class DefaultRuleActionAdapter<T> implements RuleActionAdapter<T> {
    RuleActionValidator<T> ruleActionValidator;

    public DefaultRuleActionAdapter(RuleActionValidator<T> ruleActionValidator) {
        this.ruleActionValidator = ruleActionValidator;
    }

    public RuleAction<? super T> createFromClosure(Class<T> subjectType, Closure<?> closure) {
        RuleAction<T> ruleAction = new ClosureBackedRuleAction<T>(subjectType, closure);
        return ruleActionValidator.validate(ruleAction);
    }

    public RuleAction<? super T> createFromAction(Action<? super T> action) {
        RuleAction<T> ruleAction = new NoInputsRuleAction<T>(action);
        return ruleActionValidator.validate(ruleAction);
    }
}
