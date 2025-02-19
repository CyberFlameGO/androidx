/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.wear.watchface.style.data;

import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.versionedparcelable.ParcelField;
import androidx.versionedparcelable.VersionedParcelable;
import androidx.versionedparcelable.VersionedParcelize;

import java.util.Map;

/** @hide */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@VersionedParcelize
public class PerComplicationTypeMargins implements VersionedParcelable {
    @ParcelField(1)
    @NonNull
    public Map<Integer, RectF> mPerComplicationTypeMargins;

    PerComplicationTypeMargins() {
    }

    public PerComplicationTypeMargins(
            @NonNull Map<Integer, RectF> perComplicationTypeMargins
    ) {
        mPerComplicationTypeMargins = perComplicationTypeMargins;
    }
}
