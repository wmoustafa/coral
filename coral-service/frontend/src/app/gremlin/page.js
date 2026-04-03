'use client';

import { useState } from 'react';

import NavBar from '@/app/components/NavBar';
import GremlinForm from '@/app/components/Forms/GremlinForm';
import GremlinResultCard from '@/app/components/Cards/GremlinResultCard';
import Image from 'next/image';

export default function GremlinPage() {
  const [gremlinResult, setGremlinResult] = useState(null);
  const [visualizationId, setVisualizationId] = useState(null);

  const handleGremlinFetch = (result) => {
    setGremlinResult(result);
  };

  const handleVisualizationFetch = (imageId) => {
    setVisualizationId(imageId);
  };

  return (
    <>
      <NavBar />

      <Image
        width='200'
        height='200'
        className='mx-auto pt-4'
        src='/coral-logo.jpg'
        alt='Coral Logo'
      />

      <GremlinForm 
        onGremlinFetchComplete={handleGremlinFetch}
        onVisualizationFetchComplete={handleVisualizationFetch}
      />
      {gremlinResult && (
        <GremlinResultCard 
          result={gremlinResult} 
          visualizationId={visualizationId}
        />
      )}
    </>
  );
}
