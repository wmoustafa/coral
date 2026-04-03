'use client';

import { useState } from 'react';
import LoadingButton from '@/app/components/Buttons/LoadingButton';
const baseUrl = process.env.NEXT_PUBLIC_CORAL_SERVICE_API_URL;

export default function GremlinForm({ onGremlinFetchComplete, onVisualizationFetchComplete }) {
  const [isLoading, setIsLoading] = useState(false);

  async function onSubmit(event) {
    event.preventDefault();
    setIsLoading(true);

    // clear old results
    onGremlinFetchComplete(null);
    onVisualizationFetchComplete(null);

    const formData = new FormData(event.currentTarget);
    const requestBody = {
      gremlinQuery: formData.get('gremlinQuery'),
      vertexTable: formData.get('vertexTable'),
      edgeTable: formData.get('edgeTable'),
      vertexIdColumn: formData.get('vertexIdColumn'),
      edgeSrcColumn: formData.get('edgeSrcColumn'),
      edgeDstColumn: formData.get('edgeDstColumn'),
    };

    // Fetch Gremlin conversion
    await fetch(baseUrl + '/api/gremlin/convert', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(requestBody),
    })
      .then((response) => {
        if (!response.ok) {
          return response.text().then((errorMessage) => {
            throw new Error(errorMessage);
          });
        }

        return response.json();
      })
      .then((data) => {
        onGremlinFetchComplete(data);
      })
      .catch((error) => {
        console.error('Error:', error);
        onGremlinFetchComplete({ error: error.message, success: false });
        setIsLoading(false);
      });

    // Fetch RelNode visualization (optional - fails gracefully)
    try {
      const vizResponse = await fetch(baseUrl + '/api/gremlin/visualize', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'application/json',
        },
        body: JSON.stringify(requestBody),
      });

      if (vizResponse.ok) {
        const vizData = await vizResponse.json();
        onVisualizationFetchComplete(vizData.relNodeImageID);
      } else {
        console.warn('Visualization not available');
        onVisualizationFetchComplete(null);
      }
    } catch (error) {
      console.warn('Visualization error:', error);
      onVisualizationFetchComplete(null);
    }
    
    setIsLoading(false);
  }

  return (
    <>
      <div className='flex flex-1 flex-col justify-center px-6 pt-2 pb-12 lg:px-8'>
        <div className='mt-10 sm:mx-auto sm:w-full sm:max-w-2xl'>
          <form
            className='space-y-6'
            action='#'
            method='POST'
            onSubmit={onSubmit}
          >
            <div className='col-span-full'>
              <label
                htmlFor='gremlinQuery'
                className='block text-3xl font-medium leading-6 text-gray-900 mb-6'
              >
                Gremlin to Spark SQL
              </label>
              <div className='mt-2'>
                <textarea
                  id='gremlinQuery'
                  name='gremlinQuery'
                  rows='4'
                  className='block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-coral-blue sm:text-md sm:leading-6 font-courier'
                  placeholder="g.V().has('name', 'Alice').outE('friend').inV()"
                  required
                ></textarea>
              </div>
              <p className='mt-2 text-sm text-gray-600'>
                Enter your Gremlin graph traversal query
              </p>
            </div>

            <div className='grid grid-cols-1 gap-x-6 gap-y-4 sm:grid-cols-2'>
              <div>
                <label
                  htmlFor='vertexTable'
                  className='block text-sm font-medium leading-6 text-gray-900'
                >
                  Vertex Table
                </label>
                <div className='mt-2'>
                  <input
                    type='text'
                    id='vertexTable'
                    name='vertexTable'
                    className='block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-coral-blue sm:text-sm sm:leading-6'
                    placeholder='default.people'
                    defaultValue='default.people'
                    required
                  />
                </div>
              </div>

              <div>
                <label
                  htmlFor='edgeTable'
                  className='block text-sm font-medium leading-6 text-gray-900'
                >
                  Edge Table
                </label>
                <div className='mt-2'>
                  <input
                    type='text'
                    id='edgeTable'
                    name='edgeTable'
                    className='block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-coral-blue sm:text-sm sm:leading-6'
                    placeholder='default.relationships'
                    defaultValue='default.relationships'
                    required
                  />
                </div>
              </div>

              <div>
                <label
                  htmlFor='vertexIdColumn'
                  className='block text-sm font-medium leading-6 text-gray-900'
                >
                  Vertex ID Column
                </label>
                <div className='mt-2'>
                  <input
                    type='text'
                    id='vertexIdColumn'
                    name='vertexIdColumn'
                    className='block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-coral-blue sm:text-sm sm:leading-6'
                    placeholder='person_id'
                    defaultValue='person_id'
                    required
                  />
                </div>
              </div>

              <div>
                <label
                  htmlFor='edgeSrcColumn'
                  className='block text-sm font-medium leading-6 text-gray-900'
                >
                  Edge Source Column
                </label>
                <div className='mt-2'>
                  <input
                    type='text'
                    id='edgeSrcColumn'
                    name='edgeSrcColumn'
                    className='block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-coral-blue sm:text-sm sm:leading-6'
                    placeholder='src_person'
                    defaultValue='src_person'
                    required
                  />
                </div>
              </div>

              <div>
                <label
                  htmlFor='edgeDstColumn'
                  className='block text-sm font-medium leading-6 text-gray-900'
                >
                  Edge Destination Column
                </label>
                <div className='mt-2'>
                  <input
                    type='text'
                    id='edgeDstColumn'
                    name='edgeDstColumn'
                    className='block w-full rounded-md border-0 py-1.5 text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 placeholder:text-gray-400 focus:ring-2 focus:ring-inset focus:ring-coral-blue sm:text-sm sm:leading-6'
                    placeholder='dst_person'
                    defaultValue='dst_person'
                    required
                  />
                </div>
              </div>
            </div>

            <div>
              {isLoading ? (
                <LoadingButton text='Converting' />
              ) : (
                <button
                  type='submit'
                  className='flex w-full justify-center rounded-md px-4 py-2 text-md font-semibold leading-6 text-white shadow-sm bg-coral-blue hover:bg-coral-blue-lighter focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-coral-blue'
                >
                  Convert to Spark SQL
                </button>
              )}
            </div>
          </form>
        </div>
      </div>
    </>
  );
}
