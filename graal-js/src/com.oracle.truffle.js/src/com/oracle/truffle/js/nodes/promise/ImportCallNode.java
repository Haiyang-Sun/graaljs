/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.promise;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.arguments.AccessIndexedArgumentNode;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.nodes.control.TryCatchNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSPromise;
import com.oracle.truffle.js.runtime.objects.JSModuleRecord;
import com.oracle.truffle.js.runtime.objects.PromiseCapabilityRecord;
import com.oracle.truffle.js.runtime.objects.PromiseReactionRecord;
import com.oracle.truffle.js.runtime.objects.ScriptOrModule;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.Pair;

/**
 * Represents the import call expression syntax: {@code import(specifier)}.
 */
public class ImportCallNode extends JavaScriptNode {
    @Child private JavaScriptNode argRefNode;
    @Child private JavaScriptNode activeScriptOrModuleNode;
    @Child private NewPromiseCapabilityNode newPromiseCapabilityNode;
    @Child private JSToStringNode toStringNode;
    @Child private PromiseReactionJobNode promiseReactionJobNode;

    // lazily initialized
    @Child private JSFunctionCallNode callRejectNode;
    @Child private TryCatchNode.GetErrorObjectNode getErrorObjectNode;

    private final JSContext context;
    private final ValueProfile typeProfile = ValueProfile.createClassProfile();

    protected ImportCallNode(JSContext context, JavaScriptNode argRefNode, JavaScriptNode activeScriptOrModuleNode) {
        this.context = context;
        this.argRefNode = argRefNode;
        this.activeScriptOrModuleNode = activeScriptOrModuleNode;
        this.newPromiseCapabilityNode = NewPromiseCapabilityNode.create(context);
        this.toStringNode = JSToStringNode.create();
        this.promiseReactionJobNode = PromiseReactionJobNode.create(context);
    }

    public static ImportCallNode create(JSContext context, JavaScriptNode argRefNode, JavaScriptNode activeScriptOrModuleNode) {
        return new ImportCallNode(context, argRefNode, activeScriptOrModuleNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object referencingScriptOrModule = activeScriptOrModuleNode.execute(frame);
        Object specifier = argRefNode.execute(frame);
        String specifierString;
        try {
            specifierString = toStringNode.executeString(specifier);
        } catch (Throwable ex) {
            if (TryCatchNode.shouldCatch(ex, typeProfile)) {
                return newRejectedPromiseFromException(ex);
            } else {
                throw ex;
            }
        }
        return hostImportModuleDynamically(referencingScriptOrModule, specifierString);
    }

    private DynamicObject hostImportModuleDynamically(Object referencingScriptOrModule, String specifier) {
        JSRealm realm = context.getRealm();
        if (context.hasImportModuleDynamicallyCallbackBeenSet()) {
            DynamicObject promise = context.hostImportModuleDynamically(realm, (ScriptOrModule) referencingScriptOrModule, specifier);
            if (promise == null) {
                return newRejectedPromiseFromException(createTypeErrorCannotImport(specifier));
            }
            assert JSPromise.isJSPromise(promise);
            return promise;
        } else {
            // default implementation
            PromiseCapabilityRecord promiseCapability = newPromiseCapability();
            context.promiseEnqueueJob(realm, createImportModuleDynamicallyJob((ScriptOrModule) referencingScriptOrModule, specifier, promiseCapability));
            return promiseCapability.getPromise();
        }
    }

    private PromiseCapabilityRecord newPromiseCapability() {
        if (newPromiseCapabilityNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            newPromiseCapabilityNode = insert(NewPromiseCapabilityNode.create(context));
        }
        return newPromiseCapabilityNode.executeDefault();
    }

    private DynamicObject newRejectedPromiseFromException(Throwable ex) {
        if (callRejectNode == null || getErrorObjectNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callRejectNode = insert(JSFunctionCallNode.createCall());
            getErrorObjectNode = insert(TryCatchNode.GetErrorObjectNode.create(context));
        }
        PromiseCapabilityRecord promiseCapability = newPromiseCapability();
        callRejectNode.executeCall(JSArguments.createOneArg(Undefined.instance, promiseCapability.getReject(), getErrorObjectNode.execute(ex)));
        return promiseCapability.getPromise();
    }

    @TruffleBoundary
    private static JSException createTypeErrorCannotImport(String specifier) {
        return Errors.createError("Cannot dynamically import module: " + specifier);
    }

    /**
     * Returns a promise job that performs both HostImportModuleDynamically and FinishDynamicImport.
     */
    public DynamicObject createImportModuleDynamicallyJob(ScriptOrModule referencingScriptOrModule, String specifier, PromiseCapabilityRecord promiseCapability) {
        Pair<ScriptOrModule, String> request = new Pair<>(referencingScriptOrModule, specifier);
        return promiseReactionJobNode.execute(PromiseReactionRecord.create(promiseCapability, createImportModuleDynamicallyHandler(), true), request);
    }

    /**
     * Returns a handler function to be used together with a PromiseReactionJob in order to perform
     * the steps of both HostImportModuleDynamically and FinishDynamicImport.
     */
    private DynamicObject createImportModuleDynamicallyHandler() {
        JSFunctionData functionData = context.getOrCreateBuiltinFunctionData(JSContext.BuiltinFunctionKey.ImportModuleDynamically, (c) -> createImportModuleDynamicallyHandlerImpl(c));
        return JSFunction.create(context.getRealm(), functionData);
    }

    private static JSFunctionData createImportModuleDynamicallyHandlerImpl(JSContext context) {
        class ImportModuleDynamicallyRootNode extends JavaScriptRootNode {
            @Child private JavaScriptNode argumentNode = AccessIndexedArgumentNode.create(0);

            @SuppressWarnings("unchecked")
            @Override
            public Object execute(VirtualFrame frame) {
                Pair<ScriptOrModule, String> request = (Pair<ScriptOrModule, String>) argumentNode.execute(frame);
                ScriptOrModule referencingScriptOrModule = request.getFirst();
                String specifier = request.getSecond();

                JSModuleRecord moduleRecord = context.getEvaluator().hostResolveImportedModule(context, referencingScriptOrModule, specifier);
                context.getEvaluator().moduleInstantiation(moduleRecord);
                context.getEvaluator().moduleEvaluation(context.getRealm(), moduleRecord);
                return finishDynamicImport(moduleRecord, referencingScriptOrModule, specifier);
            }

            private Object finishDynamicImport(JSModuleRecord moduleRecord, ScriptOrModule referencingScriptOrModule, String specifier) {
                // Note: PromiseReactionJob performs the promise rejection and resolution.
                assert moduleRecord == context.getEvaluator().hostResolveImportedModule(context, referencingScriptOrModule, specifier);
                // Evaluate has already been invoked on moduleRecord and successfully completed.
                assert moduleRecord.isEvaluated();
                return context.getEvaluator().getModuleNamespace(moduleRecord);
            }
        }
        CallTarget callTarget = Truffle.getRuntime().createCallTarget(new ImportModuleDynamicallyRootNode());
        return JSFunctionData.createCallOnly(context, callTarget, 0, "");
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return ImportCallNode.create(context, cloneUninitialized(argRefNode), cloneUninitialized(activeScriptOrModuleNode));
    }
}
