/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A registry for {@link Configurator} instances.
 *
 * <p>This class is responsible for maintaining a list of configurators and providing access to
 * them. The default registry can be obtained using {@link #getDefaultRegistry()}.
 */
@Internal
public final class ConfiguratorRegistry {
  private static ConfiguratorRegistry instance;
  private static boolean isConfiguratorsSet;
  private List<Configurator> configurators = Collections.emptyList();

  ConfiguratorRegistry() {}

  /**
   * Returns the default global instance of the configurator registry.
   */
  public static synchronized ConfiguratorRegistry getDefaultRegistry() {
    if (instance == null) {
      instance = new ConfiguratorRegistry();
    }
    return instance;
  }

  /**
   * Sets the configurators in this registry. This method can only be called once.
   *
   * @param configurators the configurators to set
   * @throws IllegalStateException if this method is called more than once
   */
  public synchronized void setConfigurators(List<Configurator> configurators) {
    if (isConfiguratorsSet) {
      throw new IllegalStateException("Configurators are already set");
    }
    configurators = Collections.unmodifiableList(new ArrayList<>(configurators));
    isConfiguratorsSet = true;
  }

  /**
   * Returns a list of the configurators in this registry.
   */
  public synchronized List<Configurator> getConfigurators() {
    return configurators;
  }
}
