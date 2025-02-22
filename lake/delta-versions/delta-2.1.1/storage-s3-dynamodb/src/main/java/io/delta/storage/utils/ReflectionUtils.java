/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.delta.storage.utils;

import com.amazonaws.auth.AWSCredentialsProvider;
import org.apache.hadoop.conf.Configuration;

import java.util.Arrays;

public class ReflectionUtils {

    private static boolean readsCredsFromHadoopConf(Class<?> awsCredentialsProviderClass) {
        return Arrays.stream(awsCredentialsProviderClass.getConstructors())
                .anyMatch(constructor -> constructor.getParameterCount() == 1 &&
                        Arrays.equals(constructor.getParameterTypes(), new Class[]{Configuration.class}));
    }

    /**
     * Create AWS credentials provider from given provider classname and {@link Configuration}.
     *
     * It first check if AWS Credentials Provider class has constructor Hadoop configuration as parameter.
     *   If yes - create instance of class using this constructor.
     *   If no - create instance with empty parameters constructor.
     *
     * @param credentialsProviderClassName Fully qualified name of the desired credentials provider class.
     * @param hadoopConf Hadoop configuration, used to create instance of AWS credentials
     *                                      provider, if supported.
     * @return {@link AWSCredentialsProvider} object, instantiated from the class @see {credentialsProviderClassName}
     * @throws ReflectiveOperationException When AWS credentials provider constrictor do not matched.
     *                                      Means class has neither an constructor with no args as input
     *                                      nor constructor with only Hadoop configuration as argument.
     */
    public static AWSCredentialsProvider createAwsCredentialsProvider(
            String credentialsProviderClassName,
            Configuration hadoopConf) throws ReflectiveOperationException {
        Class<?> awsCredentialsProviderClass = Class.forName(credentialsProviderClassName);
        if (readsCredsFromHadoopConf(awsCredentialsProviderClass))
            return (AWSCredentialsProvider) awsCredentialsProviderClass
                    .getConstructor(Configuration.class)
                    .newInstance(hadoopConf);
        else
            return (AWSCredentialsProvider) awsCredentialsProviderClass.getConstructor().newInstance();
    }

}
