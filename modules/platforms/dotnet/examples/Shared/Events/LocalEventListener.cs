﻿/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace Apache.Ignite.Examples.Shared.Events
{
    using System;
    using System.Threading;
    using Apache.Ignite.Core.Events;

    /// <summary>
    /// Local event listener.
    /// </summary>
    public class LocalEventListener : IEventListener<IEvent>
    {
        /** Count of received events. */
        private int _eventsReceived;

        /// <summary>
        /// Gets the count of received events.
        /// </summary>
        public int EventsReceived
        {
            get { return _eventsReceived; }
        }

        /// <summary>
        /// Determines whether specified event passes this filter.
        /// </summary>
        /// <param name="evt">Event.</param>
        /// <returns>Value indicating whether specified event passes this filter.</returns>
        public bool Invoke(IEvent evt)
        {
            Interlocked.Increment(ref _eventsReceived);

            Console.WriteLine("Local listener received an event [evt={0}]", evt.Name);

            return true;
        }
    }
}
