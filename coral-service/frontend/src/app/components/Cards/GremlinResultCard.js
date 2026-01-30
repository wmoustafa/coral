'use client';

import Zoom from 'react-medium-image-zoom';
import 'react-medium-image-zoom/dist/styles.css';

const baseUrl = process.env.NEXT_PUBLIC_CORAL_SERVICE_API_URL;

export default function GremlinResultCard({ result, visualizationId }) {
  if (!result) return null;

  if (result.error || !result.success) {
    return (
      <div className='mx-auto max-w-4xl px-6 pb-12'>
        <div className='rounded-lg bg-red-50 p-6 shadow-md'>
          <h3 className='text-lg font-semibold text-red-800 mb-2'>Error</h3>
          <pre className='text-sm text-red-700 whitespace-pre-wrap font-courier'>
            {result.error || 'Conversion failed'}
          </pre>
        </div>
      </div>
    );
  }

  return (
    <div className='mx-auto max-w-4xl px-6 pb-12'>
      <div className='space-y-6'>
        {/* Gremlin Query */}
        <div className='rounded-lg bg-gray-50 p-6 shadow-md'>
          <h3 className='text-lg font-semibold text-gray-900 mb-3'>
            Gremlin Query
          </h3>
          <pre className='text-sm text-gray-800 whitespace-pre-wrap font-courier bg-white p-4 rounded border border-gray-200'>
            {result.gremlinQuery}
          </pre>
        </div>

        {/* RelNode Diagram Visualization */}
        {visualizationId && (
          <div className='rounded-lg bg-purple-50 p-6 shadow-md'>
            <h3 className='text-lg font-semibold text-purple-900 mb-3'>
              RelNode Diagram (Execution Plan)
            </h3>
            <div className='bg-white p-4 rounded border border-purple-200'>
              <Zoom>
                <img
                  src={`${baseUrl}/api/visualizations/${visualizationId}`}
                  alt='RelNode Diagram'
                  className='mx-auto max-w-full h-auto'
                />
              </Zoom>
            </div>
            <p className='mt-2 text-sm text-purple-700'>
              Click the diagram to zoom in
            </p>
          </div>
        )}

        {/* Spark SQL */}
        <div className='rounded-lg bg-green-50 p-6 shadow-md'>
          <h3 className='text-lg font-semibold text-green-900 mb-3'>
            Spark SQL
          </h3>
          <pre className='text-sm text-green-800 whitespace-pre-wrap font-courier bg-white p-4 rounded border border-green-200'>
            {result.sparkSql}
          </pre>
        </div>

        {/* Coral IR (RelNode) - Collapsible */}
        <details className='rounded-lg bg-blue-50 p-6 shadow-md'>
          <summary className='text-lg font-semibold text-blue-900 cursor-pointer hover:text-blue-700'>
            Coral IR (RelNode Text) - Click to expand
          </summary>
          <pre className='mt-3 text-sm text-blue-800 whitespace-pre-wrap font-courier bg-white p-4 rounded border border-blue-200'>
            {result.relNode}
          </pre>
        </details>
      </div>
    </div>
  );
}
